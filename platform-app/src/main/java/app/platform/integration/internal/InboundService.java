package app.platform.integration.internal;

import app.platform.data.BackgroundRecords;
import app.platform.data.BackgroundWriteException;
import app.platform.data.BackgroundWrites;
import app.platform.data.BackgroundWrites.Assignment;
import app.platform.data.BackgroundWrites.Source;
import app.platform.data.BackgroundWrites.Value;
import app.platform.data.DataRecord;
import app.platform.integration.Integrations;
import app.platform.integration.Integrations.ScopeAction;
import app.platform.integration.adapter.CanonicalEntity;
import app.platform.integration.adapter.Connector;
import app.platform.integration.adapter.PayloadException;
import app.platform.integration.internal.ConnectionStore.Connection;
import app.platform.integration.internal.InboundDirectory.Target;
import app.platform.integration.internal.InboundSignatures.Headers;
import app.platform.integration.internal.LinkStore.Link;
import app.platform.integration.internal.Mapper.Inbound;
import app.platform.integration.internal.MappingStore.Live;
import app.platform.metadata.Metadata;
import app.platform.metadata.ObjectDefinition;
import app.platform.sharedkernel.TenantId;
import app.platform.tenant.TenantContext;
import app.platform.tenant.TenantContexts;
import app.platformapi.ApiException;
import app.platformapi.ErrorCode;
import app.platformapi.InboundResultView;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Receives a change from an external system (Sprint 28, ADR-0221, ADR-0222). The order is fixed in code and tested:
 *
 * <ol>
 *   <li>the size of the body and the allowance of the source, before anything is looked up or parsed;
 *   <li>the connection, found from the public identity in the address alone (never from the host or the caller);
 *   <li>the signature over the exact bytes, then the nonce (a replay is refused);
 *   <li>only then the payload is read, mapped, and written through the platform's write pipeline as the platform,
 * within
 *       the scope of the connection.
 * </ol>
 *
 * <p>An unknown connection, a connection that is not switched on, a malformed header, a stale time, a replay and a
 * wrong signature are <em>one</em> answer ({@code UNAUTHENTICATED}); the receiver cannot be used to learn which
 * connections exist. After the signature the sender is known and gets precise, fixed words. Nothing the caller sent, no
 * record
 * value, no header and no signature is logged, audited or put in a metric tag.
 */
@Service
class InboundService {

    private static final String ENTITY = "[a-z][a-z0-9-]{0,39}";
    private static final String IDENTITY = "[0-9a-f]{32}";

    private final InboundDirectory directory;
    private final InboundSignatures signatures;
    private final InboundLimiter limiter;
    private final ReplayStore replays;
    private final ConnectionStore connections;
    private final ConnectorRegistry registry;
    private final MappingStore mappings;
    private final MappingCompiler compiler;
    private final Mapper mapper;
    private final LinkStore links;
    private final DeliveryStore deliveries;
    private final BackgroundRecords records;
    private final BackgroundWrites writes;
    private final Integrations integrations;
    private final Metadata metadata;
    private final TenantContexts contexts;
    private final TransactionTemplate transaction;
    private final TransactionTemplate nested;
    private final IntegrationProperties.Flow flow;
    private final MeterRegistry meters;
    private final Clock clock;

    InboundService(InboundDirectory directory, InboundSignatures signatures, InboundLimiter limiter,
            ReplayStore replays, ConnectionStore connections, ConnectorRegistry registry, MappingStore mappings,
            MappingCompiler compiler, Mapper mapper, LinkStore links, DeliveryStore deliveries,
            BackgroundRecords records, BackgroundWrites writes, Integrations integrations, Metadata metadata,
            TenantContexts contexts, PlatformTransactionManager transactions, IntegrationProperties properties,
            MeterRegistry meters, Clock clock) {
        this.directory = directory;
        this.signatures = signatures;
        this.limiter = limiter;
        this.replays = replays;
        this.connections = connections;
        this.registry = registry;
        this.mappings = mappings;
        this.compiler = compiler;
        this.mapper = mapper;
        this.links = links;
        this.deliveries = deliveries;
        this.records = records;
        this.writes = writes;
        this.integrations = integrations;
        this.metadata = metadata;
        this.contexts = contexts;
        this.transaction = new TransactionTemplate(transactions);
        this.nested = new TransactionTemplate(transactions);
        this.nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
        this.flow = properties.flow();
        this.meters = meters;
        this.clock = clock;
    }

    /** The most bytes of a body the receiver accepts. */
    int maxBytes() {
        return flow.maxInboundBytes();
    }

    /** What the second step decides: the end of the delivery, the word the sender gets and the record touched. */
    record Outcome(String state, String reason, String word, UUID record) {

        static Outcome failed(FlowReason reason) {
            return new Outcome("FAILED", reason.key(), null, null);
        }

        static Outcome skipped(FlowReason reason) {
            return new Outcome("SKIPPED", reason.key(), reason == FlowReason.UNCHANGED ? "unchanged" : "skipped",
                    null);
        }
    }

    /**
     * Handles one change.
     *
     * @param source the address the call came from, for the allowance
     * @param inboundId the public identity of the connection, from the address
     * @param entity the entity, from the address
     * @param body the exact bytes of the body, never longer than {@link #maxBytes()}
     */
    InboundResultView receive(String source, String inboundId, String entity, byte[] body, Headers headers) {
        if (!limiter.allow(source)) {
            count("rate_limited");
            throw ApiException.rateLimited(60);
        }
        boolean shaped = inboundId != null && inboundId.matches(IDENTITY) && entity != null
                && entity.matches(ENTITY);
        Optional<Target> target = shaped ? directory.find(inboundId) : Optional.empty();
        Target known = target.filter(Target::on).orElse(null);
        TenantId tenant = known == null ? null : known.tenant();
        Connection connection = known == null ? null : contexts.call(TenantContext.of(tenant),
                () -> transaction.execute(status -> connections.findById(known.connection()).orElse(null)));
        String credential = connection == null ? null
                : connection.definition().settings().get("inboundCredential");
        // In the connection's own context: a call that arrives on another organization's host must still find the
        // credential of the connection it names, and no other.
        java.util.function.Supplier<Boolean> check = () -> signatures.verify(tenant, credential,
                shaped ? entity : "", body, headers, clock.instant(), flow.replayWindow());
        boolean signed = Boolean.TRUE.equals(tenant == null ? check.get()
                : contexts.call(TenantContext.of(tenant), check));
        if (connection == null || !signed || !registry.find(connection.connectorType()).isPresent()) {
            count("unauthenticated");
            throw new ApiException(ErrorCode.UNAUTHENTICATED);
        }
        return contexts.call(TenantContext.of(tenant), () -> accept(connection, entity, body, headers));
    }

    private InboundResultView accept(Connection connection, String entity, byte[] body, Headers headers) {
        UUID actor = connection.id();
        String nonceHash = Mapper.sha256(headers.nonce());
        Boolean first = transaction.execute(status -> {
            replays.purge(connection.id(), flow.replayWindow(), 50);
            return replays.firstSight(connection.id(), nonceHash, actor);
        });
        if (!Boolean.TRUE.equals(first)) {
            count("replayed");
            throw new ApiException(ErrorCode.UNAUTHENTICATED);
        }
        UUID change = UUID.randomUUID();
        Outcome outcome = transaction.execute(status -> process(connection, entity, body, change));
        count(outcome.state().toLowerCase(Locale.ROOT));
        if ("FAILED".equals(outcome.state())) {
            throw failure(outcome.reason());
        }
        return new InboundResultView(outcome.word());
    }

    /** The second step, in one transaction: map, write, link and record how it ended. */
    private Outcome process(Connection connection, String entity, byte[] body, UUID change) {
        Live live = mappings.liveForEntity(connection.id(), entity).orElse(null);
        if (live == null) {
            return Outcome.failed(FlowReason.NO_MAPPING);
        }
        UUID actor = connection.id();
        String object = live.mapping().object();
        UUID version = live.version().id();
        UUID delivery = deliveries.start(connection.id(), version, "IN", "UPSERT", change, object, null, actor)
                .orElseThrow();
        Outcome outcome = decide(connection, live, entity, body, change);
        deliveries.finish(delivery, outcome.state(), outcome.reason(), null, actor);
        return outcome;
    }

    private Outcome decide(Connection connection, Live live, String entity, byte[] body, UUID change) {
        String object = live.mapping().object();
        Connector connector = registry.find(connection.connectorType()).orElseThrow();
        ObjectDefinition definition = metadata.object(object).orElse(null);
        if (definition == null) {
            return Outcome.failed(FlowReason.UNEXPECTED);
        }
        MappingCompiler.Result compiled = compiler.compile(entity, live.version().definition(), definition);
        if (!compiled.ok()) {
            return Outcome.failed(FlowReason.TRANSFORM_FAILED);
        }
        CanonicalEntity incoming;
        try {
            incoming = connector.receive(connection.definition().settings(), entity, body);
        } catch (PayloadException unreadable) {
            return Outcome.failed(FlowReason.PAYLOAD_UNREADABLE);
        }
        return applyEntity(connection, live, compiled.mapping(), incoming, change);
    }

    /**
     * Applies one entity that was read from outside, through the same code as a signed change (Sprint 29A, ADR-0241):
     * the same mapping, the same scope, the same link, the same write pipeline as the platform. The caller has already
     * decided <em>who is asking</em>; this decides nothing about that. Run inside a transaction; the write itself is in
     * a savepoint of its own, so a refused row undoes only itself.
     */
    Outcome applyEntity(Connection connection, Live live, CompiledMapping mapping, CanonicalEntity incoming,
            UUID change) {
        String object = live.mapping().object();
        String external = incoming.externalId();
        if (external == null || external.isEmpty() || external.length() > 200) {
            return Outcome.failed(FlowReason.ID_MISSING);
        }
        // Two changes for one entity are handled one after the other, so one entity never becomes two records.
        links.lock(connection.id(), external);
        Optional<Link> link = links.byExternal(connection.id(), object, external);
        return link.isPresent() ? update(connection, live, mapping, incoming, link.get(), change)
                : create(connection, live, mapping, incoming, external, change);
    }

    /**
     * Whether the entity can be mapped at all, without writing anything: the stable reason it cannot, or null. Used to
     * check a whole file before the first row of it is written.
     */
    String mappingProblem(CompiledMapping mapping, CanonicalEntity incoming) {
        Inbound in = mapper.in(mapping, incoming, null, clock.instant());
        return in.failure() == null ? null : in.failure().reason();
    }

    private Outcome update(Connection connection, Live live, CompiledMapping mapping, CanonicalEntity incoming,
            Link link, UUID change) {
        String object = live.mapping().object();
        if (!mapping.inboundUpdate()) {
            return Outcome.skipped(FlowReason.UPDATE_NOT_ALLOWED);
        }
        if (!integrations.allows(connection.id(), object, ScopeAction.UPDATE)) {
            return Outcome.skipped(FlowReason.OUT_OF_SCOPE);
        }
        DataRecord record = records.find(object, link.recordId()).filter(found -> !found.deleted()).orElse(null);
        if (record == null) {
            return Outcome.failed(FlowReason.RECORD_GONE);
        }
        Inbound in = mapper.in(mapping, incoming, record.updatedAt(), clock.instant());
        if (in.failure() != null) {
            return new Outcome("FAILED", in.failure().reason(), null, null);
        }
        if (in.assignments().isEmpty()) {
            return Outcome.skipped(in.skipped().isEmpty() ? FlowReason.NOTHING_TO_APPLY : FlowReason.PLATFORM_NEWER);
        }
        String hash = hashOf(in);
        if (hash.equals(link.lastHash())) {
            return Outcome.skipped(FlowReason.UNCHANGED);
        }
        try {
            nested.executeWithoutResult(status -> writes.update(Source.integration(connection.id(), change), object,
                    record.id(), assignments(in)));
        } catch (BackgroundWriteException refused) {
            return new Outcome("FAILED", "write_" + refused.reason().key(), null, null);
        }
        links.synced(link.id(), link.version(), hash, "IN", connection.id());
        return new Outcome("DONE", null, "accepted", record.id());
    }

    private Outcome create(Connection connection, Live live, CompiledMapping mapping, CanonicalEntity incoming,
            String external, UUID change) {
        String object = live.mapping().object();
        if (!mapping.inboundCreate()) {
            return Outcome.skipped(FlowReason.UNKNOWN_ENTITY);
        }
        if (!integrations.allows(connection.id(), object, ScopeAction.CREATE)) {
            return Outcome.skipped(FlowReason.OUT_OF_SCOPE);
        }
        Optional<UUID> owner = mappings.publishedBy(live.version().id());
        if (owner.isEmpty()) {
            return Outcome.failed(FlowReason.UNEXPECTED);
        }
        Inbound in = mapper.in(mapping, incoming, null, clock.instant());
        if (in.failure() != null) {
            return new Outcome("FAILED", in.failure().reason(), null, null);
        }
        String hash = hashOf(in);
        DataRecord made;
        try {
            made = nested.execute(status -> writes.create(Source.integration(connection.id(), change), object,
                    owner.get(), assignments(in)));
        } catch (BackgroundWriteException refused) {
            return new Outcome("FAILED", "write_" + refused.reason().key(), null, null);
        }
        links.insert(connection.id(), object, made.id(), external, hash, "IN", connection.id());
        return new Outcome("DONE", null, "accepted", made.id());
    }

    private static String hashOf(Inbound in) {
        return Mapper.hash(in.assignments().stream().collect(Collectors.toMap(Mapper.Assignment::platform,
                Mapper.Assignment::text)));
    }

    private static List<Assignment> assignments(Inbound in) {
        return in.assignments().stream().map(item -> new Assignment(item.platform(), new Value.Literal(item.text())))
                .toList();
    }

    private ApiException failure(String reason) {
        String words = FlowReason.wordsFor(reason);
        if (reason.equals(FlowReason.NO_MAPPING.key())) {
            return ApiException.notFound("The connection has nothing to receive for this entity.");
        }
        if (reason.equals("write_record_locked") || reason.equals("write_concurrent_modification")
                || reason.equals(FlowReason.RECORD_GONE.key())) {
            return new ApiException(ErrorCode.CONFLICT, words);
        }
        if (reason.equals(FlowReason.UNEXPECTED.key())) {
            return new ApiException(ErrorCode.INTERNAL_ERROR);
        }
        return new ApiException(ErrorCode.VALIDATION_ERROR, words);
    }

    private void count(String result) {
        meters.counter("platform.integration.inbound", "result", result).increment();
    }
}
