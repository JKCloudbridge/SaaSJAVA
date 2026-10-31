package app.platform.integration.internal;

import app.platform.data.BackgroundRecords;
import app.platform.data.DataRecord;
import app.platform.integration.adapter.CanonicalEntity;
import app.platform.integration.adapter.Connector;
import app.platform.integration.adapter.Operation;
import app.platform.integration.adapter.OutboundRequest;
import app.platform.integration.adapter.PayloadException;
import app.platform.integration.internal.ConnectionStore.Connection;
import app.platform.integration.internal.EgressClient.Answer;
import app.platform.integration.internal.LinkStore.Link;
import app.platform.integration.internal.Mapper.Outbound;
import app.platform.integration.internal.MappingStore.Version;
import app.platform.metadata.Metadata;
import app.platform.metadata.ObjectDefinition;
import app.platform.observability.ErrorReport;
import app.platform.observability.ErrorTracker;
import app.platform.sharedkernel.ActorId;
import app.platform.sharedkernel.TenantId;
import app.platform.tenant.TenantContext;
import app.platform.tenant.TenantContexts;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sends one record to an external system and records how it went (Sprint 28, ADR-0220). It runs after the commit of
 * the handler that decided to send, never inside a person's save and never inside a database transaction while it waits
 * for the other side: a short transaction prepares the request (reads the record as it is now, maps it under the
 * version the delivery started with), the call is made with none open, and a short transaction records the end.
 *
 * <p><strong>What this promises.</strong> One attempt for each event and connection, whichever instance handled the
 * event, and the end of it recorded as a delivery with a stable reason: nothing is lost without a trace. It does
 * <em>not</em> retry, queue, break a circuit or rate limit a connection: that is Sprint 29. A send that was under way
 * when an instance stopped stays pending and is shown as interrupted; it is not repeated, because it may have reached
 * the other side.
 *
 * <p>Nothing here logs, audits or puts in a metric tag a record value, a payload, a header or a name an organization
 * chose; a failure of the framework itself is reported to the error tracking by its class name only.
 */
@Component
class OutboundSender {

    /** What the handler hands over: identifiers only. */
    record Work(TenantId tenant, UUID delivery, UUID connection, UUID version, String eventType, String object,
            UUID record) {
    }

    /** What the first transaction decides: the end of the delivery, or a request ready to make. */
    private record Prepared(String state, String reason, Ready ready) {

        static Prepared done(String state, FlowReason reason) {
            return new Prepared(state, reason.key(), null);
        }

        static Prepared failed(String reasonKey) {
            return new Prepared("FAILED", reasonKey, null);
        }
    }

    private record Ready(Connection connection, Connector connector, Operation operation, OutboundRequest request,
            String hash, EndpointRules.Endpoint endpoint, String entityKind) {
    }

    private static final UUID ACTOR = ActorId.SYSTEM.value();
    private static final int PURGE_BATCH = 50;

    private final TransactionTemplate transaction;
    private final TenantContexts contexts;
    private final ConnectionStore connections;
    private final ConnectorRegistry registry;
    private final MappingStore mappings;
    private final MappingCompiler compiler;
    private final Mapper mapper;
    private final LinkStore links;
    private final DeliveryStore deliveries;
    private final BackgroundRecords records;
    private final Metadata metadata;
    private final CredentialHeaders credentials;
    private final EgressClient egress;
    private final EndpointRules.Policy policy;
    private final IntegrationProperties.Flow flow;
    private final ErrorTracker tracker;
    private final MeterRegistry meters;
    private final Clock clock;
    private final Semaphore slots;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final ExecutorService calls = Executors.newVirtualThreadPerTaskExecutor();

    OutboundSender(PlatformTransactionManager transactions, TenantContexts contexts, ConnectionStore connections,
            ConnectorRegistry registry, MappingStore mappings, MappingCompiler compiler, Mapper mapper,
            LinkStore links, DeliveryStore deliveries, BackgroundRecords records, Metadata metadata,
            CredentialHeaders credentials, EgressClient egress, EndpointRules.Policy policy,
            IntegrationProperties properties, ErrorTracker tracker, MeterRegistry meters, Clock clock) {
        this.transaction = new TransactionTemplate(transactions);
        this.contexts = contexts;
        this.connections = connections;
        this.registry = registry;
        this.mappings = mappings;
        this.compiler = compiler;
        this.mapper = mapper;
        this.links = links;
        this.deliveries = deliveries;
        this.records = records;
        this.metadata = metadata;
        this.credentials = credentials;
        this.egress = egress;
        this.policy = policy;
        this.flow = properties.flow();
        this.tracker = tracker;
        this.meters = meters;
        this.clock = clock;
        this.slots = new Semaphore(flow.maxConcurrentSends());
    }

    @PreDestroy
    void close() {
        workers.shutdownNow();
        calls.shutdownNow();
    }

    /** Hands the work to a thread of its own; the caller (an after-commit step) never waits. */
    void submit(Work work) {
        workers.execute(() -> runSafely(work));
    }

    /** Runs the work on this thread, for a test that drives the sender without waiting. */
    void runNow(Work work) {
        runSafely(work);
    }

    private void runSafely(Work work) {
        try {
            contexts.run(TenantContext.of(work.tenant()), () -> run(work));
        } catch (RuntimeException failure) {
            tracker.track(ErrorReport.current(new ConnectionTesting.IntegrationFailure(failure), "POST",
                    "/integration/outbound"));
            try {
                contexts.run(TenantContext.of(work.tenant()), () -> end(work, "FAILED", FlowReason.UNEXPECTED.key(),
                        null));
            } catch (RuntimeException ignored) {
                // The delivery stays pending and is shown as interrupted; nothing more can be done from here.
            }
        }
    }

    private void run(Work work) {
        if (!slots.tryAcquire()) {
            end(work, "FAILED", FlowReason.BUSY.key(), null);
            return;
        }
        try {
            Prepared prepared = transaction.execute(status -> prepare(work));
            if (prepared.ready() == null) {
                end(work, prepared.state(), prepared.reason(), null);
                return;
            }
            Ready ready = prepared.ready();
            long started = System.nanoTime();
            Answer answer = call(work, ready);
            Timer.builder("platform.integration.send.duration").tag("result", answer.failure() == null ? "answered"
                    : "failed").register(meters).record(Duration.ofNanos(System.nanoTime() - started));
            transaction.executeWithoutResult(status -> finish(work, ready, answer));
        } finally {
            slots.release();
        }
    }

    // ---- the first transaction: decide and build the request ----

    private Prepared prepare(Work work) {
        Connection connection = connections.findById(work.connection()).orElse(null);
        if (connection == null || !ConnectionMachine.isUsable(connection.state())) {
            return Prepared.done("SKIPPED", FlowReason.CONNECTION_OFF);
        }
        Version version = mappings.versionById(work.version()).orElse(null);
        ObjectDefinition object = metadata.object(work.object()).orElse(null);
        Connector connector = registry.find(connection.connectorType()).orElse(null);
        if (version == null || object == null || connector == null) {
            return Prepared.done("FAILED", FlowReason.UNEXPECTED);
        }
        String entity = mappings.mappingById(version.mappingId()).map(MappingStore.Mapping::entity).orElse(null);
        MappingCompiler.Result compiled = entity == null ? null
                : compiler.compile(entity, version.definition(), object);
        if (compiled == null || !compiled.ok()) {
            return Prepared.done("FAILED", FlowReason.TRANSFORM_FAILED);
        }
        Optional<Link> link = links.byRecord(connection.id(), work.object(), work.record());
        boolean removal = "record.deleted".equals(work.eventType());
        CanonicalEntity entityToSend;
        Operation operation;
        String hash = null;
        if (removal) {
            if (link.isEmpty()) {
                return Prepared.done("SKIPPED", FlowReason.NOT_LINKED);
            }
            operation = Operation.DELETE;
            entityToSend = new CanonicalEntity(entity, link.get().externalId(), Map.of());
        } else {
            DataRecord record = records.find(work.object(), work.record()).filter(found -> !found.deleted())
                    .orElse(null);
            if (record == null) {
                return Prepared.done("SKIPPED", FlowReason.RECORD_GONE);
            }
            operation = link.isEmpty() ? Operation.CREATE : Operation.UPDATE;
            Outbound out = mapper.out(compiled.mapping(), records.engineValues(work.object(), record),
                    link.map(Link::externalId).orElse(null), clock.instant());
            if (out.failure() != null) {
                return Prepared.failed(out.failure().reason());
            }
            entityToSend = out.entity();
            hash = Mapper.hash(entityToSend);
            if (operation == Operation.UPDATE && hash.equals(link.get().lastHash())) {
                return Prepared.done("SKIPPED", FlowReason.UNCHANGED);
            }
        }
        OutboundRequest request;
        try {
            request = connector.send(connection.definition().settings(), operation, entityToSend);
        } catch (PayloadException unwritable) {
            return Prepared.done("FAILED", FlowReason.PAYLOAD_UNWRITABLE);
        }
        if (!EndpointRules.validPath(request.path()) || request.body().length > flow.maxRequestBytes()) {
            return Prepared.done("FAILED", FlowReason.PAYLOAD_UNWRITABLE);
        }
        EndpointRules.Result address = EndpointRules.parse(connection.definition().baseUrl(), policy);
        if (!address.ok()) {
            return Prepared.failed(TestReason.ADDRESS_NOT_ALLOWED.key());
        }
        return new Prepared(null, null, new Ready(connection, connector, operation, request, hash, address.endpoint(),
                entity));
    }

    // ---- the call: no transaction, one deadline ----

    private Answer call(Work work, Ready ready) {
        CredentialHeaders.Resolved credential = credentials.resolve(work.tenant(), ready.connection().definition());
        if (credential.failure() != null) {
            return Answer.failed(credential.failure());
        }
        EgressClient.Call call = new EgressClient.Call(ready.endpoint(), ready.request().method().name(),
                ready.request().path(), credential.header(), ready.request().contentType(), ready.request().body(),
                flow.sendTimeout(), flow.maxResponseBytes());
        Future<Answer> pending = calls.submit(() -> egress.call(call));
        try {
            return pending.get(flow.sendTimeout().toMillis() + 1000, TimeUnit.MILLISECONDS);
        } catch (TimeoutException tooSlow) {
            pending.cancel(true);
            return Answer.failed(TestReason.TIMED_OUT);
        } catch (InterruptedException interrupted) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            return Answer.failed(TestReason.UNEXPECTED);
        } catch (ExecutionException failed) {
            tracker.track(ErrorReport.current(new ConnectionTesting.IntegrationFailure(failed.getCause()), "POST",
                    "/integration/outbound"));
            return Answer.failed(TestReason.UNEXPECTED);
        }
    }

    // ---- the last transaction: record how it ended ----

    private void finish(Work work, Ready ready, Answer answer) {
        if (answer.failure() != null) {
            endIn(work, "FAILED", answer.failure().key(), null);
            return;
        }
        int status = answer.status();
        var settings = ready.connection().definition().settings();
        if (!ready.connector().accepted(settings, ready.operation(), status)) {
            boolean refused = status == 401 || status == 403;
            endIn(work, "FAILED", refused ? TestReason.AUTHENTICATION_REJECTED.key() : FlowReason.REJECTED.key(),
                    status);
            return;
        }
        UUID connection = ready.connection().id();
        switch (ready.operation()) {
            case CREATE -> {
                String external = externalIdOf(ready, answer);
                if (external == null) {
                    endIn(work, "FAILED", FlowReason.NO_IDENTIFIER.key(), status);
                    return;
                }
                links.insert(connection, work.object(), work.record(), external, ready.hash(), "OUT", ACTOR);
            }
            case UPDATE -> links.byRecord(connection, work.object(), work.record())
                    .ifPresent(link -> links.synced(link.id(), link.version(), ready.hash(), "OUT", ACTOR));
            case DELETE -> links.byRecord(connection, work.object(), work.record())
                    .ifPresent(link -> links.remove(link.id(), ACTOR));
            default -> throw new IllegalStateException("Unknown operation");
        }
        endIn(work, "DONE", null, status);
    }

    /** The identifier the other side gave the entity, or null when the answer has none that can be kept. */
    private String externalIdOf(Ready ready, Answer answer) {
        try {
            String id = ready.connector().receive(ready.connection().definition().settings(), ready.entityKind(),
                    answer.body()).externalId();
            return id == null || id.isEmpty() || id.length() > 200 ? null : id;
        } catch (PayloadException unreadable) {
            return null;
        }
    }

    private void end(Work work, String state, String reason, Integer httpStatus) {
        transaction.executeWithoutResult(status -> endIn(work, state, reason, httpStatus));
    }

    private void endIn(Work work, String state, String reason, Integer httpStatus) {
        if (deliveries.finish(work.delivery(), state, reason, httpStatus, ACTOR)) {
            meters.counter("platform.integration.delivery", "direction", "out", "result",
                    state.toLowerCase(Locale.ROOT), "reason", reason == null ? "none" : reason).increment();
        }
        deliveries.purge(work.connection(), flow.deliveryRetention(), PURGE_BATCH);
    }
}
