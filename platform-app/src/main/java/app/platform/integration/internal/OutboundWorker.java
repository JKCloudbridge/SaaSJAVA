package app.platform.integration.internal;

import app.platform.data.BackgroundRecords;
import app.platform.data.DataRecord;
import app.platform.integration.adapter.CanonicalEntity;
import app.platform.integration.adapter.Connector;
import app.platform.integration.adapter.FileFlowConnector;
import app.platform.integration.adapter.FileLayout;
import app.platform.integration.adapter.JobState;
import app.platform.integration.adapter.Operation;
import app.platform.integration.adapter.OutboundRequest;
import app.platform.integration.adapter.PayloadException;
import app.platform.integration.adapter.ProbeRequest;
import app.platform.integration.adapter.Transport;
import app.platform.integration.internal.AttemptStore.Outcome;
import app.platform.integration.internal.ConnectionStore.Connection;
import app.platform.integration.internal.DeliveryStore.Claimed;
import app.platform.integration.internal.DeliveryStore.Delivery;
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
import app.platform.sharedkernel.audit.AuditOutcome;
import app.platform.sharedkernel.audit.AuditRecord;
import app.platform.sharedkernel.audit.AuditRecorder;
import app.platform.sharedkernel.lifecycle.WorkerStop;
import app.platform.tenant.SystemScope;
import app.platform.tenant.TenantContext;
import app.platform.tenant.TenantContexts;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sends the queued deliveries (Sprint 29, ADR-0227 to ADR-0235). It replaces the single attempt of Sprint 28: the
 * handler of an event only writes a delivery row, and this worker, on any instance, claims what is due with a lease,
 * decides whether the connection may send now, makes one attempt with no transaction held, and records how it ended.
 * A failure that may pass is tried again after a wait kept in the row; one that will not, or one that used every
 * attempt, ends as a dead letter; an outage opens the circuit and the queue waits. Nothing a restart cuts off is lost:
 * a claimed delivery comes back when its lease ends.
 *
 * <p><strong>Three steps per attempt.</strong> First a transaction prepares the request (reads the record as it is
 * <em>now</em>, maps it under the version the delivery is bound to, so a retry never sends a stale payload and a change
 * of the mapping never rewrites a waiting delivery) and asks the gate whether the connection may send. Then the call,
 * with nothing open, under one hard deadline. Last a transaction records the end: the link, the state, the attempt and
 * the breaker's view of the other side.
 *
 * <p>It is the one class, besides the tester, that calls out, and the one place of the module that enters the system
 * scope {@code integration_dispatch}, which it enters only to claim and count. Nothing here logs, audits or puts in a
 * metric tag a record value, a payload, a header, an address or an external identifier; a failure of the framework
 * itself is reported to the error tracking by its class name only.
 */
@Component
class OutboundWorker implements SmartLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(OutboundWorker.class);
    private static final UUID ACTOR = ActorId.SYSTEM.value();
    private static final int PURGE_BATCH = 50;
    private static final int DEFER_BATCH = 2000;

    /** What the first transaction decides: nothing more to do, or a request ready to make. */
    private record Begun(Ready ready) {

        static Begun nothing() {
            return new Begun(null);
        }
    }

    private record Ready(Connection connection, Connector connector, Operation operation, OutboundRequest request,
            String hash, EndpointRules.Endpoint endpoint, String entityKind, Delivery delivery, boolean probe,
            Instant startedAt) {
    }

    /** How an attempt ended, before it is written down. */
    private record Ended(boolean success, RetryPolicy.Verdict verdict, Integer status, String statusClass,
            Integer retryAfter) {
    }

    private final TransactionTemplate transaction;
    private final TenantContexts contexts;
    private final ConnectionStore connections;
    private final ConnectorRegistry registry;
    private final MappingStore mappings;
    private final MappingCompiler compiler;
    private final Mapper mapper;
    private final LinkStore links;
    private final DeliveryStore deliveries;
    private final AttemptStore attempts;
    private final FlowGate gate;
    private final BackgroundRecords records;
    private final Metadata metadata;
    private final CredentialHeaders credentials;
    private final CredentialLogins logins;
    private final EgressClient egress;
    private final FileTransport fileTransport;
    private final PollStore polls;
    private final FilePoller filePoller;
    private final ConnectionFailures connectionFailures;
    private final EndpointRules.Policy policy;
    private final IntegrationProperties.Flow flow;
    private final IntegrationProperties.Reliability settings;
    private final AuditRecorder audit;
    private final ErrorTracker tracker;
    private final MeterRegistry meters;
    private final Clock clock;
    private final Semaphore slots;
    private final Semaphore pollSlots;
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicLong depth = new AtomicLong();
    private final AtomicLong age = new AtomicLong();
    private final AtomicLong dead = new AtomicLong();
    private volatile ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private volatile ExecutorService calls = Executors.newVirtualThreadPerTaskExecutor();
    private ScheduledExecutorService scheduler;
    private volatile boolean running;

    OutboundWorker(PlatformTransactionManager transactions, TenantContexts contexts, ConnectionStore connections,
            ConnectorRegistry registry, MappingStore mappings, MappingCompiler compiler, Mapper mapper,
            LinkStore links, DeliveryStore deliveries, AttemptStore attempts, FlowGate gate,
            BackgroundRecords records, Metadata metadata, CredentialHeaders credentials, CredentialLogins logins,
            EgressClient egress, FileTransport fileTransport, PollStore polls, FilePoller filePoller,
            ConnectionFailures connectionFailures, EndpointRules.Policy policy, IntegrationProperties properties,
            AuditRecorder audit, ErrorTracker tracker, MeterRegistry meters, Clock clock) {
        this.transaction = new TransactionTemplate(transactions);
        this.contexts = contexts;
        this.connections = connections;
        this.registry = registry;
        this.mappings = mappings;
        this.compiler = compiler;
        this.mapper = mapper;
        this.links = links;
        this.deliveries = deliveries;
        this.attempts = attempts;
        this.gate = gate;
        this.records = records;
        this.metadata = metadata;
        this.credentials = credentials;
        this.logins = logins;
        this.egress = egress;
        this.fileTransport = fileTransport;
        this.polls = polls;
        this.filePoller = filePoller;
        this.connectionFailures = connectionFailures;
        this.policy = policy;
        this.flow = properties.flow();
        this.settings = properties.reliability();
        this.audit = audit;
        this.tracker = tracker;
        this.meters = meters;
        this.clock = clock;
        this.slots = new Semaphore(flow.maxConcurrentSends());
        this.pollSlots = new Semaphore(properties.files().maxConcurrentPolls());
        Gauge.builder("platform.integration.queue.depth", depth, AtomicLong::get).register(meters);
        Gauge.builder("platform.integration.queue.age", age, AtomicLong::get).baseUnit("seconds").register(meters);
    }

    // ---- the loop ----

    /**
     * One look: claims what is due, as many as there are free places to send from, and starts each on a thread of its
     * own. It does not wait for them.
     *
     * @return how many were claimed
     */
    synchronized int pollOnce() {
        return claimDeliveries() + claimPolls();
    }

    /**
     * Claims the file polls that are due, as many as there are free places to look from, and starts each on a thread of
     * its own (Sprint 29A, ADR-0242). It is here, and not in the poller, because this is the one class that may enter
     * the system scope that lets one statement see the due polls of every organization; the look itself runs in the
     * organization's own tenant context.
     */
    private int claimPolls() {
        int free = pollSlots.availablePermits();
        if (free <= 0 || !filePoller.enabled()) {
            return 0;
        }
        List<PollStore.Claimed> due = asDispatcher(() -> polls.claimDue(free, filePoller.lease()));
        int started = 0;
        for (PollStore.Claimed poll : due) {
            if (!pollSlots.tryAcquire()) {
                // Cannot happen with a single poller; if it does the lease simply expires and another look takes it.
                continue;
            }
            started++;
            inFlight.incrementAndGet();
            workers.execute(() -> {
                try {
                    contexts.run(TenantContext.of(new TenantId(poll.tenantId())), () -> filePoller.run(poll));
                } catch (RuntimeException failure) {
                    LOG.warn("The file poller failed: {}", failure.getClass().getName());
                    meters.counter("platform.integration.worker", "result", "failed").increment();
                } finally {
                    inFlight.decrementAndGet();
                    pollSlots.release();
                }
            });
        }
        return started;
    }

    private int claimDeliveries() {
        int free = slots.availablePermits();
        if (free <= 0) {
            return 0;
        }
        List<Claimed> claimed = asDispatcher(() -> deliveries.claim(Math.min(settings.batchSize(), free),
                settings.perOrganization(), settings.lease()));
        for (Claimed delivery : claimed) {
            if (!slots.tryAcquire()) {
                // Cannot happen with a single poller; if it does the lease simply expires and another look takes it.
                continue;
            }
            inFlight.incrementAndGet();
            workers.execute(() -> {
                try {
                    handle(delivery);
                } finally {
                    inFlight.decrementAndGet();
                    slots.release();
                }
            });
        }
        refreshGauges();
        return claimed.size();
    }

    /** How many deliveries this instance is working on. */
    int inFlight() {
        return inFlight.get();
    }

    /**
     * Looks and waits until this instance has nothing under way and the last look found nothing, for a test or a
     * shutdown that must not leave a send half done. Returns false if the time ran out first.
     */
    boolean drain(Duration limit) {
        long deadline = System.nanoTime() + limit.toNanos();
        int quiet = 0;
        while (System.nanoTime() < deadline) {
            int claimed = pollOnce();
            if (claimed == 0 && inFlight.get() == 0) {
                if (++quiet >= 2) {
                    return true;
                }
            } else {
                quiet = 0;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private void handle(Claimed claimed) {
        try {
            contexts.run(TenantContext.of(new TenantId(claimed.tenantId())), () -> process(claimed));
        } catch (RuntimeException failure) {
            // The type only: the message of a library can quote data. The lease ends and the delivery comes back.
            LOG.warn("The outbound worker failed on a delivery: {}", failure.getClass().getName());
            tracker.track(ErrorReport.current(new ConnectionTesting.IntegrationFailure(failure), "WORKER",
                    "integration.outbound"));
            meters.counter("platform.integration.worker", "result", "failed").increment();
        }
    }

    private void process(Claimed claimed) {
        if ("WAITING_REMOTE".equals(claimed.state())) {
            pollRemote(claimed);
            return;
        }
        Begun begun = transaction.execute(status -> begin(claimed));
        if (begun == null || begun.ready() == null) {
            return;
        }
        Ready ready = begun.ready();
        long started = System.nanoTime();
        Answer answer = call(claimed, ready);
        long took = Duration.ofNanos(System.nanoTime() - started).toMillis();
        Timer.builder("platform.integration.send.duration").tag("result", answer.failure() == null ? "answered"
                : "failed").register(meters).record(Duration.ofMillis(took));
        transaction.executeWithoutResult(status -> conclude(claimed, ready, answer, took));
    }

    // ---- the first transaction: decide, build the request, ask the gate ----

    private Begun begin(Claimed claimed) {
        Instant now = clock.instant();
        Connection connection = connections.findById(claimed.connectionId()).orElse(null);
        if (connection == null) {
            deliveries.end(claimed.id(), "SKIPPED", FlowReason.CONNECTION_REMOVED.key(), null, null);
            return Begun.nothing();
        }
        if (!ConnectionMachine.isUsable(connection.state())) {
            Delivery held = deliveries.findAny(claimed.id()).orElse(null);
            if (held != null && "SENDING".equals(held.state())) {
                // Holding it back turns it into PENDING, so the lost attempt is written down first.
                attempts.record(held.id(), connection.id(), held.generation(), Math.max(1, held.attempts()),
                        held.lastAttemptAt() == null ? now : held.lastAttemptAt(), 0, Outcome.INTERRUPTED,
                        FlowReason.INTERRUPTED.key(), "none", null);
            }
            // Not lost: a connection that is switched off holds its queue until it is switched on again.
            Instant until = now.plus(settings.queue().idleRecheck());
            deliveries.deferConnection(connection.id(), until, FlowReason.CONNECTION_OFF.key(), DEFER_BATCH);
            deliveries.defer(claimed.id(), until, FlowReason.CONNECTION_OFF.key());
            return Begun.nothing();
        }
        Delivery delivery = deliveries.findAny(claimed.id()).orElse(null);
        if (delivery == null || !DeliveryState.valueOf(delivery.state()).open()) {
            return Begun.nothing();
        }
        if ("SENDING".equals(delivery.state())) {
            // An instance stopped while this was being sent. It may have reached the other side, so the repeat carries
            // the same key; the lost attempt is written down so the story reads true.
            attempts.record(delivery.id(), connection.id(), delivery.generation(), Math.max(1, delivery.attempts()),
                    delivery.lastAttemptAt() == null ? now : delivery.lastAttemptAt(), 0, Outcome.INTERRUPTED,
                    FlowReason.INTERRUPTED.key(), "none", null);
            if (!RetryPolicy.mayTryAgain(settings.retry(), delivery.attempts())) {
                deadLetter(connection, delivery, FlowReason.INTERRUPTED.key(), "none", null);
                return Begun.nothing();
            }
        }
        Prepared prepared = prepare(claimed, connection);
        if (prepared.ready() == null) {
            finishWithoutCall(connection, delivery, prepared, now);
            return Begun.nothing();
        }
        FlowGate.Verdict verdict = gate.admit(connection.id(), delivery.id(), now);
        if (!verdict.admitted()) {
            if (FlowReason.CIRCUIT_OPEN.key().equals(verdict.reason())) {
                deliveries.deferConnection(connection.id(), verdict.retryAt(), verdict.reason(), DEFER_BATCH);
            } else {
                meters.counter("platform.integration.throttled").increment();
            }
            deliveries.defer(delivery.id(), verdict.retryAt(), verdict.reason());
            return Begun.nothing();
        }
        Delivery admitted = deliveries.admit(delivery.id(), settings.lease()).orElse(null);
        if (admitted == null) {
            gate.report(connection.id(), false, RetryPolicy.Health.NEUTRAL, null, now);
            return Begun.nothing();
        }
        Ready ready = prepared.ready();
        return new Begun(new Ready(ready.connection(), ready.connector(), ready.operation(), ready.request(),
                ready.hash(), ready.endpoint(), ready.entityKind(), admitted, verdict.probe(), now));
    }

    /** What preparing decided: the end of the delivery without a call, or a request ready to make. */
    private record Prepared(String state, String reason, Ready ready) {

        static Prepared skipped(FlowReason reason) {
            return new Prepared("SKIPPED", reason.key(), null);
        }

        static Prepared permanent(String reasonKey) {
            return new Prepared("DEAD_LETTERED", reasonKey, null);
        }
    }

    private Prepared prepare(Claimed claimed, Connection connection) {
        Version version = mappings.versionById(claimed.mappingVersionId()).orElse(null);
        ObjectDefinition object = metadata.object(claimed.object()).orElse(null);
        Connector connector = registry.find(connection.connectorType()).orElse(null);
        if (version == null || object == null || connector == null) {
            return Prepared.permanent(FlowReason.UNEXPECTED.key());
        }
        String entity = mappings.mappingById(version.mappingId()).map(MappingStore.Mapping::entity).orElse(null);
        MappingCompiler.Result compiled = entity == null ? null
                : compiler.compile(entity, version.definition(), object);
        if (compiled == null || !compiled.ok()) {
            return Prepared.permanent(FlowReason.TRANSFORM_FAILED.key());
        }
        Optional<Link> link = links.byRecord(connection.id(), claimed.object(), claimed.recordId());
        boolean removal = "DELETE".equals(claimed.kind());
        CanonicalEntity entityToSend;
        Operation operation;
        String hash = null;
        if (removal) {
            if (link.isEmpty()) {
                return Prepared.skipped(FlowReason.NOT_LINKED);
            }
            operation = Operation.DELETE;
            entityToSend = new CanonicalEntity(entity, link.get().externalId(), Map.of());
        } else {
            DataRecord record = records.find(claimed.object(), claimed.recordId()).filter(found -> !found.deleted())
                    .orElse(null);
            if (record == null) {
                return Prepared.skipped(FlowReason.RECORD_GONE);
            }
            operation = link.isEmpty() ? Operation.CREATE : Operation.UPDATE;
            Outbound out = mapper.out(compiled.mapping(), records.engineValues(claimed.object(), record),
                    link.map(Link::externalId).orElse(null), clock.instant());
            if (out.failure() != null) {
                return Prepared.permanent(out.failure().reason());
            }
            CanonicalEntity produced = out.entity();
            // A file has no service that names the entity: its identifier is the record's own, so a person reading the
            // file and the platform agree on what a row is about (ADR-0240).
            entityToSend = connector.transport() != Transport.HTTP && produced.externalId() == null
                    ? new CanonicalEntity(produced.kind(), claimed.recordId().toString(), produced.fields())
                    : produced;
            hash = Mapper.hash(entityToSend);
            if (operation == Operation.UPDATE && hash.equals(link.get().lastHash())) {
                return Prepared.skipped(FlowReason.UNCHANGED);
            }
        }
        OutboundRequest request;
        try {
            request = connector.send(connection.definition().settings(), operation, entityToSend);
        } catch (PayloadException unwritable) {
            return Prepared.permanent(FlowReason.PAYLOAD_UNWRITABLE.key());
        }
        if (!EndpointRules.validPath(request.path()) || request.body().length > flow.maxRequestBytes()
                || !ExtraHeaders.safe(request.headers())) {
            return Prepared.permanent(FlowReason.PAYLOAD_UNWRITABLE.key());
        }
        EndpointRules.Result address = EndpointRules.parse(connection.definition().baseUrl(), policy,
                connector.transport());
        if (!address.ok()) {
            return Prepared.permanent(TestReason.ADDRESS_NOT_ALLOWED.key());
        }
        return new Prepared(null, null, new Ready(connection, connector, operation, request, hash,
                address.endpoint(), entity, null, false, null));
    }

    /** Ends a delivery that needs no call: nothing to do, or a failure that no waiting will mend. */
    private void finishWithoutCall(Connection connection, Delivery delivery, Prepared prepared, Instant now) {
        if ("DEAD_LETTERED".equals(prepared.state())) {
            // It counts as an attempt, so the log tells why nothing was sent.
            Delivery counted = deliveries.admit(delivery.id(), settings.lease()).orElse(delivery);
            attempts.record(counted.id(), connection.id(), counted.generation(), Math.max(1, counted.attempts()), now,
                    0, Outcome.PERMANENT, prepared.reason(), "none", null);
            deadLetter(connection, counted, prepared.reason(), "none", null);
            return;
        }
        deliveries.end(delivery.id(), prepared.state(), prepared.reason(), null, null);
        count("out", prepared.state().toLowerCase(Locale.ROOT), prepared.reason());
    }

    // ---- the call: no transaction, one deadline ----

    private Answer call(Claimed claimed, Ready ready) {
        if (ready.connector().transport() == Transport.SFTP) {
            return putFile(claimed, ready);
        }
        CredentialHeaders.Resolved credential = credentials.resolve(new TenantId(claimed.tenantId()),
                ready.connection().definition());
        if (credential.failure() != null) {
            return Answer.failed(credential.failure());
        }
        Map<String, String> headers = new HashMap<>(ready.request().headers());
        String idempotency = ready.connector().idempotencyHeader(ready.connection().definition().settings());
        if (idempotency != null && ready.delivery().idempotencyKey() != null
                && ExtraHeaders.safe(Map.of(idempotency, ready.delivery().idempotencyKey()))) {
            headers.put(idempotency, ready.delivery().idempotencyKey());
        }
        EgressClient.Call call = new EgressClient.Call(ready.endpoint(), ready.request().method().name(),
                ready.request().path(), credential.header(), ready.request().contentType(), ready.request().body(),
                flow.sendTimeout(), flow.maxResponseBytes(), headers);
        return bounded(() -> egress.call(call));
    }

    /**
     * Writes the file of one send to the server (Sprint 29A, ADR-0240). The name is made from the delivery and its
     * generation, so a repeat after a lost answer is the same name: if the file is there with the size of this content
     * it was already written and nothing is done again. The content goes to a temporary name that starts with a dot and
     * is renamed into place, so a reader of the directory never meets half a file. The answer is the same shape the web
     * client gives, so the classification, the retries, the breaker and the log apply unchanged: a written file is a
     * {@code 201} whose body names the entity (the record's own identifier), a refusal is a stable reason.
     *
     * <p>What is and is not promised: the file is created once per delivery generation as long as the server keeps it
     * until the repeat; a receiver that removes the file between the write and a repeat caused by a lost answer sees
     * the name again, which is why the name, not the content, is the key to deduplicate on.
     */
    private Answer putFile(Claimed claimed, Ready ready) {
        CredentialLogins.Resolved resolved = logins.resolve(new TenantId(claimed.tenantId()),
                ready.connection().definition());
        if (resolved.failure() != null) {
            return Answer.failed(resolved.failure());
        }
        CredentialLogins.Login login = resolved.login();
        try {
            Map<String, String> connectionSettings = ready.connection().definition().settings();
            FileFlowConnector connector = (FileFlowConnector) ready.connector();
            FileLayout layout = connector.layout(connectionSettings);
            Duration limit = layout.operationTimeout().compareTo(settings.lease().minusSeconds(15)) < 0
                    ? layout.operationTimeout() : settings.lease().minusSeconds(15);
            FileTransport.Target target = new FileTransport.Target(ready.endpoint(), connectionSettings.get("hostKey"),
                    limit);
            String name = FilePaths.outboundName(ready.delivery().id(), ready.delivery().generation(),
                    layout.format());
            byte[] content = ready.request().body();
            String reference = claimed.recordId().toString();
            return bounded(() -> writeFile(target, login, layout, name, content, reference), limit);
        } finally {
            login.destroy();
        }
    }

    private Answer writeFile(FileTransport.Target target, CredentialLogins.Login login, FileLayout layout,
            String name, byte[] content, String reference) {
        try (FileVisit visit = fileTransport.open(target, login)) {
            String path = FilePaths.join(layout.outbox(), name);
            Optional<FileVisit.Entry> there = visit.stat(path);
            if (there.isPresent()) {
                if (there.get().regular() && there.get().size() == content.length) {
                    return written(reference);
                }
                return Answer.failed(TestReason.FILE_FAILED);
            }
            String temporary = FilePaths.join(layout.outbox(), FilePaths.temporaryName(name, UUID.randomUUID()));
            visit.create(temporary, content);
            Optional<FileVisit.Entry> stored = visit.stat(temporary);
            if (stored.isEmpty() || stored.get().size() != content.length) {
                return Answer.failed(TestReason.FILE_FAILED);
            }
            visit.rename(temporary, path);
            return written(reference);
        } catch (TransportFailure failure) {
            return Answer.failed(failure.reason());
        }
    }

    private static Answer written(String reference) {
        return new Answer(null, 201, ("{\"id\":\"" + reference + "\"}").getBytes(
                java.nio.charset.StandardCharsets.UTF_8));
    }

    private Answer bounded(java.util.concurrent.Callable<Answer> work) {
        return bounded(work, flow.sendTimeout());
    }

    private Answer bounded(java.util.concurrent.Callable<Answer> work, Duration limit) {
        Future<Answer> pending = calls.submit(work);
        try {
            return pending.get(limit.toMillis() + 1000, TimeUnit.MILLISECONDS);
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

    private void conclude(Claimed claimed, Ready ready, Answer answer, long tookMillis) {
        Instant now = clock.instant();
        Delivery delivery = ready.delivery();
        Connection connection = ready.connection();
        Map<String, String> settingsOfConnection = connection.definition().settings();
        Integer status = answer.failure() == null ? answer.status() : null;
        String statusClass = status == null ? "none" : RetryPolicy.statusClass(status);

        if (answer.failure() == null && ready.connector().accepted(settingsOfConnection, ready.operation(), status)) {
            Optional<String> job = ready.connector().remoteJob(settingsOfConnection, ready.operation(), status,
                    answer.body()).filter(path -> EndpointRules.validPath(path) && path.length() <= 500);
            if (job.isPresent()) {
                Instant deadline = now.plus(settings.remote().deadline());
                deliveries.waitForRemote(delivery.id(), job.get(), deadline, now.plus(jittered(
                        settings.remote().pollInterval())), statusClass, status);
                attempts.record(delivery.id(), connection.id(), delivery.generation(), delivery.attempts(),
                        ready.startedAt(), tookMillis, Outcome.ACCEPTED, "waiting_remote", statusClass, status);
                gate.report(connection.id(), false, RetryPolicy.Health.ALIVE, null, now);
                count("out", "accepted", "waiting_remote");
                return;
            }
            String problem = applyEffects(ready.connection(), ready.connector(), ready.operation(),
                    ready.entityKind(), claimed.object(), claimed.recordId(), answer.body(), ready.hash());
            if (problem != null) {
                // The service accepted the record but did not say what it calls it: nothing more can come of waiting.
                failed(ready, now, tookMillis,
                        new Ended(false, new RetryPolicy.Verdict(RetryPolicy.Kind.PERMANENT, problem,
                                RetryPolicy.Health.ALIVE, false), status, statusClass, null));
                return;
            }
            deliveries.end(delivery.id(), "DONE", null, statusClass, status);
            attempts.record(delivery.id(), connection.id(), delivery.generation(), delivery.attempts(),
                    ready.startedAt(), tookMillis, Outcome.SUCCESS, null, statusClass, status);
            gate.report(connection.id(), true, RetryPolicy.Health.ALIVE, null, now);
            count("out", "done", "none");
            deliveries.purge(connection.id(), flow.deliveryRetention(), PURGE_BATCH);
            return;
        }
        RetryPolicy.Verdict verdict = answer.failure() != null ? RetryPolicy.failure(answer.failure())
                : RetryPolicy.status(status, false);
        failed(ready, now, tookMillis, new Ended(false, verdict, status, statusClass, answer.retryAfterSeconds()));
    }

    private void failed(Ready ready, Instant now, long tookMillis, Ended ended) {
        Delivery delivery = ready.delivery();
        Connection connection = ready.connection();
        RetryPolicy.Verdict verdict = ended.verdict();
        // A server that shows another key, or refuses the credential, is not tried again with the same credential: the
        // connection is stopped until a person looks, and what was to be sent waits for it (ADR-0237). Nothing is lost,
        // and nothing is dead-lettered for a fault that is not in the data.
        boolean stop = ready.connector().transport() == Transport.SFTP
                && ConnectionFailures.stops(TestReason.ofKey(verdict.reason()));
        boolean again = stop || verdict.retry() && RetryPolicy.mayTryAgain(settings.retry(), delivery.attempts());
        attempts.record(delivery.id(), connection.id(), delivery.generation(), delivery.attempts(), ready.startedAt(),
                tookMillis, again ? Outcome.RETRY : Outcome.PERMANENT, verdict.reason(), ended.statusClass(),
                ended.status());
        gate.report(connection.id(), false, verdict.health(), verdict.reason(), now);
        if (stop) {
            connectionFailures.stopUsing(connection.id(), TestReason.ofKey(verdict.reason()));
            deliveries.retryLater(delivery.id(), now.plus(settings.queue().idleRecheck()), verdict.reason(),
                    ended.statusClass(), ended.status());
            count("out", "retry", verdict.reason());
            return;
        }
        if (again) {
            Duration wait = RetryPolicy.delay(settings.retry(), delivery.attempts(),
                    ThreadLocalRandom.current().nextDouble(), verdict.honourRetryAfter() ? ended.retryAfter() : null);
            deliveries.retryLater(delivery.id(), now.plus(wait), verdict.reason(), ended.statusClass(),
                    ended.status());
            count("out", "retry", verdict.reason());
            return;
        }
        deadLetter(connection, delivery, verdict.reason(), ended.statusClass(), ended.status());
    }

    private void deadLetter(Connection connection, Delivery delivery, String reason, String statusClass,
            Integer status) {
        if (deliveries.end(delivery.id(), "DEAD_LETTERED", reason, statusClass, status)) {
            count("out", "dead_lettered", reason);
            meters.counter("platform.integration.dead").increment();
            audit.record(AuditRecord.of("integration.delivery.dead_lettered", AuditOutcome.FAILURE)
                    .forUser(ACTOR).inTenant(contexts.require().tenantId())
                    .with("integration", connection.id().toString()).with("delivery", delivery.id().toString())
                    .with("reason", reason).with("attempts", Integer.toString(delivery.attempts())));
        }
        deliveries.purge(connection.id(), flow.deliveryRetention(), PURGE_BATCH);
    }

    /**
     * Makes the changes a successful send leaves behind: the link, and nothing else. Returns the stable reason of
     * the problem, or null when all went well.
     */
    private String applyEffects(Connection connection, Connector connector, Operation operation, String entityKind,
            String object, UUID record, byte[] body, String hash) {
        UUID id = connection.id();
        switch (operation) {
            case CREATE -> {
                String external = externalIdOf(connection, connector, entityKind, body);
                if (external == null) {
                    return FlowReason.NO_IDENTIFIER.key();
                }
                links.insert(id, object, record, external, hash, "OUT", ACTOR);
            }
            case UPDATE -> links.byRecord(id, object, record)
                    .ifPresent(link -> links.synced(link.id(), link.version(), hash, "OUT", ACTOR));
            case DELETE -> links.byRecord(id, object, record).ifPresent(link -> links.remove(link.id(), ACTOR));
            default -> throw new IllegalStateException("Unknown operation");
        }
        return null;
    }

    /** The identifier the other side gave the entity, or null when the answer has none that can be kept. */
    private String externalIdOf(Connection connection, Connector connector, String entityKind, byte[] body) {
        try {
            String id = connector.receive(connection.definition().settings(), entityKind, body).externalId();
            return id == null || id.isEmpty() || id.length() > 200 ? null : id;
        } catch (PayloadException unreadable) {
            return null;
        }
    }

    // ---- long-running operations (ADR-0233) ----

    private record Poll(Connection connection, Connector connector, String path, Delivery delivery, String entityKind,
            EndpointRules.Endpoint endpoint) {
    }

    private void pollRemote(Claimed claimed) {
        Poll poll = transaction.execute(status -> beginPoll(claimed));
        if (poll == null) {
            return;
        }
        CredentialHeaders.Resolved credential = credentials.resolve(new TenantId(claimed.tenantId()),
                poll.connection().definition());
        Answer answer;
        if (credential.failure() != null) {
            answer = Answer.failed(credential.failure());
        } else {
            ProbeRequest question = poll.connector().jobRequest(poll.connection().definition().settings(),
                    poll.path());
            EgressClient.Call call = new EgressClient.Call(poll.endpoint(), question.method().name(), question.path(),
                    credential.header(), null, new byte[0], flow.sendTimeout(), flow.maxResponseBytes());
            answer = bounded(() -> egress.call(call));
        }
        transaction.executeWithoutResult(status -> concludePoll(claimed, poll, answer));
    }

    private Poll beginPoll(Claimed claimed) {
        Instant now = clock.instant();
        Connection connection = connections.findById(claimed.connectionId()).orElse(null);
        Delivery delivery = deliveries.findAny(claimed.id()).orElse(null);
        if (delivery == null || !"WAITING_REMOTE".equals(delivery.state())) {
            return null;
        }
        if (connection == null) {
            deliveries.end(delivery.id(), "SKIPPED", FlowReason.CONNECTION_REMOVED.key(), null, null);
            return null;
        }
        if (delivery.deadlineAt() != null && now.isAfter(delivery.deadlineAt())) {
            attempts.record(delivery.id(), connection.id(), delivery.generation(), Math.max(1, delivery.attempts()),
                    now, 0, Outcome.PERMANENT, FlowReason.REMOTE_EXPIRED.key(), "none", null);
            deadLetter(connection, delivery, FlowReason.REMOTE_EXPIRED.key(), "none", null);
            return null;
        }
        if (!ConnectionMachine.isUsable(connection.state())) {
            deliveries.defer(delivery.id(), now.plus(settings.queue().idleRecheck()),
                    FlowReason.CONNECTION_OFF.key());
            return null;
        }
        FlowGate.Verdict verdict = gate.admit(connection.id(), delivery.id(), now);
        if (!verdict.admitted()) {
            deliveries.defer(delivery.id(), verdict.retryAt(), verdict.reason());
            return null;
        }
        Connector connector = registry.find(connection.connectorType()).orElse(null);
        EndpointRules.Result address = EndpointRules.parse(connection.definition().baseUrl(), policy);
        String entity = mappings.versionById(delivery.mappingVersionId())
                .flatMap(version -> mappings.mappingById(version.mappingId())).map(MappingStore.Mapping::entity)
                .orElse(null);
        if (connector == null || !address.ok() || entity == null || delivery.remoteRef() == null) {
            gate.report(connection.id(), false, RetryPolicy.Health.NEUTRAL, null, now);
            deadLetter(connection, delivery, FlowReason.UNEXPECTED.key(), "none", null);
            return null;
        }
        return new Poll(connection, connector, delivery.remoteRef(), delivery, entity, address.endpoint());
    }

    private void concludePoll(Claimed claimed, Poll poll, Answer answer) {
        Instant now = clock.instant();
        Delivery delivery = poll.delivery();
        Connection connection = poll.connection();
        Map<String, String> connectionSettings = connection.definition().settings();
        if (answer.failure() != null || RetryPolicy.statusClass(answer.status()).equals("5xx")
                || answer.status() == 429 || answer.status() == 408) {
            // Asking failed, which says nothing about the job: ask again later, until the deadline ends it.
            RetryPolicy.Verdict verdict = answer.failure() != null ? RetryPolicy.failure(answer.failure())
                    : RetryPolicy.status(answer.status(), false);
            gate.report(connection.id(), false, verdict.health(), verdict.reason(), now);
            deliveries.waitForRemote(delivery.id(), delivery.remoteRef(), delivery.deadlineAt(),
                    now.plus(jittered(settings.remote().pollInterval())), RetryPolicy.statusClass(answer.status()),
                    answer.failure() == null ? answer.status() : null);
            return;
        }
        gate.report(connection.id(), false, RetryPolicy.Health.ALIVE, null, now);
        JobState state = poll.connector().jobState(connectionSettings, answer.status(), answer.body());
        String statusClass = RetryPolicy.statusClass(answer.status());
        switch (state) {
            case RUNNING -> deliveries.waitForRemote(delivery.id(), delivery.remoteRef(), delivery.deadlineAt(),
                    now.plus(jittered(settings.remote().pollInterval())), statusClass, answer.status());
            case DONE -> {
                Operation operation = switch (delivery.kind()) {
                    case "CREATE" -> Operation.CREATE;
                    case "DELETE" -> Operation.DELETE;
                    default -> Operation.UPDATE;
                };
                if (operation == Operation.UPDATE && links.byRecord(connection.id(), delivery.object(),
                        delivery.recordId()).isEmpty()) {
                    operation = Operation.CREATE;
                }
                // The hash of what was sent is not kept; the next change is simply sent again rather than skipped.
                String problem = applyEffects(connection, poll.connector(), operation, poll.entityKind(),
                        delivery.object(), delivery.recordId(), answer.body(), null);
                if (problem != null) {
                    attempts.record(delivery.id(), connection.id(), delivery.generation(),
                            Math.max(1, delivery.attempts()), now, 0, Outcome.PERMANENT, problem, statusClass,
                            answer.status());
                    deadLetter(connection, delivery, problem, statusClass, answer.status());
                    return;
                }
                deliveries.end(delivery.id(), "DONE", null, statusClass, answer.status());
                attempts.record(delivery.id(), connection.id(), delivery.generation(),
                        Math.max(1, delivery.attempts()), now, 0, Outcome.SUCCESS, null, statusClass,
                        answer.status());
                gate.report(connection.id(), true, RetryPolicy.Health.ALIVE, null, now);
                count("out", "done", "none");
            }
            case FAILED -> {
                attempts.record(delivery.id(), connection.id(), delivery.generation(),
                        Math.max(1, delivery.attempts()), now, 0, Outcome.PERMANENT, FlowReason.REMOTE_FAILED.key(),
                        statusClass, answer.status());
                deadLetter(connection, delivery, FlowReason.REMOTE_FAILED.key(), statusClass, answer.status());
            }
            default -> throw new IllegalStateException("Unknown job state");
        }
    }

    private Duration jittered(Duration base) {
        double factor = 1.0 + ThreadLocalRandom.current().nextDouble() * 0.25;
        return Duration.ofMillis((long) (base.toMillis() * factor));
    }

    private void count(String direction, String result, String reason) {
        meters.counter("platform.integration.attempts", "direction", direction, "result", result, "reason",
                reason == null ? "none" : reason).increment();
    }

    // ---- counting the queue across organizations (a gauge; never per organization) ----

    /** What this instance last measured of the queue across organizations: counts and an age, no name. */
    record Sample(long open, long oldestSeconds, long deadLetters) {
    }

    Sample sample() {
        return new Sample(depth.get(), age.get(), dead.get());
    }

    private void refreshGauges() {
        try {
            depth.set(asDispatcher(deliveries::openAcrossOrganizations));
            age.set(asDispatcher(deliveries::oldestDueAgeSeconds));
            dead.set(asDispatcher(deliveries::deadLettersAcrossOrganizations));
        } catch (RuntimeException failure) {
            LOG.warn("The outbound worker could not count: {}", failure.getClass().getName());
        }
    }

    private <T> T asDispatcher(java.util.function.Supplier<T> work) {
        return contexts.callAsSystem(SystemScope.INTEGRATION_DISPATCH, () -> transaction.execute(s -> work.get()));
    }

    // ---- lifecycle ----

    @Override
    public synchronized void start() {
        if (running || !settings.enabled()) {
            return;
        }
        // A stopped worker may be started again (a lifecycle stop and start): its executors were shut down by stop.
        if (workers.isShutdown()) {
            workers = Executors.newVirtualThreadPerTaskExecutor();
        }
        if (calls.isShutdown()) {
            calls = Executors.newVirtualThreadPerTaskExecutor();
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> new Thread(runnable, "integration-worker"));
        running = true;
        long interval = settings.pollInterval().toMillis();
        scheduler.scheduleWithFixedDelay(this::safePoll, interval, interval, TimeUnit.MILLISECONDS);
        LOG.info("The integration outbound worker started");
    }

    @Override
    public synchronized void stop() {
        running = false;
        ScheduledExecutorService polling = scheduler;
        scheduler = null;
        // The poll and the sends in hand share one grace period (they used to wait one after the other, which made the
        // stop longer than the application's phase timeout). What is cut off is claimed again when its lease ends, and
        // the key of an attempt makes a repeat safe.
        WorkerStop.drain("integration outbound worker", polling, workers);
        calls.shutdownNow();
    }

    @Override
    public void stop(Runnable callback) {
        WorkerStop.concurrently("integration outbound worker", this::stop, callback);
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void safePoll() {
        try {
            int claimed;
            do {
                claimed = pollOnce();
            } while (claimed > 0 && running);
        } catch (RuntimeException failure) {
            LOG.warn("An outbound worker poll failed: {}", failure.getClass().getName());
        }
    }
}
