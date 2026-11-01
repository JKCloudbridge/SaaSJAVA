package app.platform.integration.internal;

import app.platform.integration.adapter.CanonicalEntity;
import app.platform.integration.adapter.Connector;
import app.platform.integration.adapter.FileFlowConnector;
import app.platform.integration.adapter.FileFormatException;
import app.platform.integration.adapter.FileLayout;
import app.platform.integration.adapter.Transport;
import app.platform.integration.internal.ConnectionStore.Connection;
import app.platform.integration.internal.CredentialLogins.Login;
import app.platform.integration.internal.EndpointRules.Endpoint;
import app.platform.integration.internal.FileBatchPlan.Batch;
import app.platform.integration.internal.FileVisit.Entry;
import app.platform.integration.internal.InboundService.Outcome;
import app.platform.integration.internal.MappingStore.Live;
import app.platform.metadata.Metadata;
import app.platform.metadata.ObjectDefinition;
import app.platform.observability.ErrorReport;
import app.platform.observability.ErrorTracker;
import app.platform.sharedkernel.ActorId;
import app.platform.sharedkernel.TenantId;
import app.platform.sharedkernel.audit.AuditOutcome;
import app.platform.sharedkernel.audit.AuditRecord;
import app.platform.sharedkernel.audit.AuditRecorder;
import app.platform.tenant.TenantContexts;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Picks up the files a server leaves for the platform and applies their rows (Sprint 29A, ADR-0240 to ADR-0242). One
 * look at one connection, claimed by the outbound worker and run in the organization's own tenant context.
 *
 * <p><strong>No file is lost and no row is written twice.</strong> A file is taken in three moves that are each safe to
 * repeat: the ledger row is written first (the intent), the file is renamed from the inbox into the processing
 * directory (atomic on the server, so two instances can never take one file), and only then is it read. The rows are
 * applied in bounded batches and the progress is written in the very transaction that wrote the rows, so a restart
 * resumes at
 * exactly the first row that was not written. When every row is in, the ledger says so and the file is moved to the
 * done directory; a file that failed as a whole is moved to the error directory with a stable reason. A restart in any
 * of those places finds the ledger row and finishes the job.
 *
 * <p><strong>Whole file first.</strong> The file is read and every row is checked against the mapping before the first
 * row is written. A file with a problem is set aside <em>as a whole</em> and nothing of it is applied. Only a row that
 * the rules of the record store refuse at write time (a locked record, a rule of the organization) is set aside alone;
 * the
 * file is then moved to the error directory so a person sees it, the rows that were accepted stay, and the count says
 * how many were refused.
 *
 * <p>The breaker, the limits and the connection's own state apply as to a send: a connection that is off never looks,
 * an open circuit never looks, a throttle waits. Nothing here logs, audits or puts in a metric tag a file name, a path,
 * a row, a value, an identifier of the other system or the credential; the ledger holds hashes and counts.
 */
@Component
class FilePoller {

    private static final Logger LOG = LoggerFactory.getLogger(FilePoller.class);
    private static final String DEFAULT_RESULT = "none";

    /** What the first transaction decides: the look is over, or here is what to look with. */
    private record Begun(Connection connection, FileFlowConnector connector, FileLayout layout, Endpoint endpoint,
            Live live, CompiledMapping mapping, int previousWaiting) {
    }

    /** A file to work on in this look: its ledger row and, when it is known, its name in the processing directory. */
    private record Todo(UUID id, String processingName) {
    }

    /** A move on the server that waits for the last visit of the look, with what to record once it has happened. */
    private record Move(UUID id, String processingName, String toDirectory, String reason) {
    }

    /** What a look found, for the record of the poll. */
    private record Looked(int waiting, boolean more) {
    }

    private final TransactionTemplate transaction;
    private final TenantContexts contexts;
    private final ConnectionStore connections;
    private final ConnectorRegistry registry;
    private final MappingStore mappings;
    private final MappingCompiler compiler;
    private final InboundService inbound;
    private final PollStore polls;
    private final FileLedgerStore files;
    private final FlowGate gate;
    private final CredentialLogins logins;
    private final FileTransport transport;
    private final ConnectionFailures failures;
    private final EndpointRules.Policy policy;
    private final Metadata metadata;
    private final AuditRecorder audit;
    private final ErrorTracker tracker;
    private final MeterRegistry meters;
    private final ObservationRegistry observations;
    private final IntegrationProperties.Files settings;
    private final IntegrationProperties.Reliability reliability;
    private final Clock clock;

    FilePoller(PlatformTransactionManager transactions, TenantContexts contexts, ConnectionStore connections,
            ConnectorRegistry registry, MappingStore mappings, MappingCompiler compiler, InboundService inbound,
            PollStore polls, FileLedgerStore files, FlowGate gate, CredentialLogins logins, FileTransport transport,
            ConnectionFailures failures, EndpointRules.Policy policy, Metadata metadata, AuditRecorder audit,
            ErrorTracker tracker, MeterRegistry meters, ObservationRegistry observations,
            IntegrationProperties properties, Clock clock) {
        this.transaction = new TransactionTemplate(transactions);
        this.contexts = contexts;
        this.connections = connections;
        this.registry = registry;
        this.mappings = mappings;
        this.compiler = compiler;
        this.inbound = inbound;
        this.polls = polls;
        this.files = files;
        this.gate = gate;
        this.logins = logins;
        this.transport = transport;
        this.failures = failures;
        this.policy = policy;
        this.metadata = metadata;
        this.audit = audit;
        this.tracker = tracker;
        this.meters = meters;
        this.observations = observations;
        this.settings = properties.files();
        this.reliability = properties.reliability();
        this.clock = clock;
    }

    /** Looks at the connection of a claimed poll. In the organization's own tenant context; never throws. */
    void run(PollStore.Claimed claim) {
        Observation.createNotStarted("platform.integration.files.poll", observations).observe(() -> poll(claim));
    }

    private void poll(PollStore.Claimed claim) {
        Begun begun = null;
        try {
            begun = transaction.execute(status -> begin(claim));
            if (begun == null) {
                return;
            }
            CredentialLogins.Resolved resolved = logins.resolve(new TenantId(claim.tenantId()),
                    begun.connection().definition());
            if (resolved.failure() != null) {
                conclude(claim, begun, resolved.failure(), null);
                return;
            }
            Login login = resolved.login();
            try {
                Looked looked = look(begun, login);
                conclude(claim, begun, null, looked);
            } catch (TransportFailure failure) {
                conclude(claim, begun, failure.reason(), null);
            } finally {
                login.destroy();
            }
        } catch (RuntimeException failure) {
            // The type only: the message of a library can quote data. The look ends as failed; the lease would too.
            LOG.warn("The file poller failed: {}", failure.getClass().getName());
            tracker.track(ErrorReport.current(new ConnectionTesting.IntegrationFailure(failure), "POLL",
                    "integration.files"));
            meters.counter("platform.integration.files.polls", "result", "error").increment();
            if (begun != null) {
                Begun started = begun;
                transaction.executeWithoutResult(status -> end(claim, started, TestReason.UNEXPECTED, null));
            }
        }
    }

    // ---- the first transaction: may the connection look now, and with what ----

    private Begun begin(PollStore.Claimed claim) {
        Instant now = clock.instant();
        Connection connection = connections.findById(claim.connectionId()).orElse(null);
        Connector connector = connection == null ? null : registry.find(connection.connectorType()).orElse(null);
        if (!(connector instanceof FileFlowConnector fileConnector)
                || !fileConnector.layout(connection.definition().settings()).pulls()) {
            polls.remove(claim.id());
            return null;
        }
        FileLayout layout = fileConnector.layout(connection.definition().settings());
        int waiting = polls.find(connection.id()).map(PollStore.Poll::filesWaiting).orElse(0);
        Instant idle = now.plus(reliability.queue().idleRecheck());
        if (!ConnectionMachine.isUsable(connection.state())) {
            polls.finish(claim.id(), idle, "HELD", FlowReason.CONNECTION_OFF.key(), waiting);
            count("held", FlowReason.CONNECTION_OFF.key());
            return null;
        }
        Live live = mappings.liveForEntity(connection.id(), layout.inboundEntity()).orElse(null);
        if (live == null) {
            polls.finish(claim.id(), idle, "HELD", FlowReason.NO_MAPPING.key(), waiting);
            count("held", FlowReason.NO_MAPPING.key());
            return null;
        }
        ObjectDefinition object = metadata.object(live.mapping().object()).orElse(null);
        MappingCompiler.Result compiled = object == null ? null
                : compiler.compile(layout.inboundEntity(), live.version().definition(), object);
        if (compiled == null || !compiled.ok()) {
            polls.finish(claim.id(), idle, "HELD", FlowReason.TRANSFORM_FAILED.key(), waiting);
            count("held", FlowReason.TRANSFORM_FAILED.key());
            return null;
        }
        EndpointRules.Result address = EndpointRules.parse(connection.definition().baseUrl(), policy, Transport.SFTP);
        if (!address.ok()) {
            polls.finish(claim.id(), idle, "FAILED", TestReason.ADDRESS_NOT_ALLOWED.key(), waiting);
            count("failed", TestReason.ADDRESS_NOT_ALLOWED.key());
            return null;
        }
        FlowGate.Verdict verdict = gate.admit(connection.id(), UUID.randomUUID(), now);
        if (!verdict.admitted()) {
            polls.finish(claim.id(), verdict.retryAt(), "HELD", verdict.reason(), waiting);
            count("held", verdict.reason());
            return null;
        }
        return new Begun(connection, fileConnector, layout, address.endpoint(), live, compiled.mapping(), waiting);
    }

    // ---- the look itself: no transaction is held while the server is talked to ----

    private Looked look(Begun begun, Login login) {
        FileLayout layout = begun.layout();
        FileTransport.Target target = new FileTransport.Target(begun.endpoint(),
                begun.connection().definition().settings().get("hostKey"), layout.operationTimeout());
        Instant deadline = clock.instant().plus(settings.deadline());
        Map<UUID, String> processingNames = new HashMap<>();
        List<Todo> todo = new ArrayList<>();
        int waiting;
        boolean more;

        // First visit: find what is unfinished, take new files by renaming them out of the inbox.
        try (FileVisit visit = transport.open(target, login)) {
            List<FileLedgerStore.Entry> unfinished = inTransaction(() -> files.unfinished(begun.connection().id()));
            if (!unfinished.isEmpty()) {
                for (Entry entry : visit.list(layout.processing(), settings.maxListed())) {
                    FilePaths.claimOf(entry.name()).ifPresent(claim -> processingNames.put(claim, entry.name()));
                }
            }
            for (FileLedgerStore.Entry entry : unfinished) {
                if (entry.state().equals("CLAIMING")) {
                    if (processingNames.containsKey(entry.id())) {
                        inTransaction(() -> {
                            files.claimed(entry.id());
                            return null;
                        });
                    } else {
                        // The move never happened: the file, if it is still there, is in the inbox and is taken afresh.
                        inTransaction(() -> {
                            files.abandon(entry.id(), FlowReason.FILE_VANISHED.key());
                            return null;
                        });
                        continue;
                    }
                }
                todo.add(new Todo(entry.id(), processingNames.get(entry.id())));
            }
            List<Entry> candidates = visit.list(layout.inbox(), settings.maxListed()).stream()
                    .filter(entry -> entry.regular() && FilePaths.pickable(entry.name(), layout.format()))
                    .sorted(Comparator.comparingLong(Entry::modifiedSeconds).thenComparing(Entry::name)).toList();
            int taken = 0;
            int handled = 0;
            for (Entry candidate : candidates) {
                if (taken >= settings.maxFiles() || clock.instant().isAfter(deadline)) {
                    break;
                }
                handled++;
                String key = FilePaths.fileKey(candidate.name(), candidate.size(), candidate.modifiedSeconds());
                if (inTransaction(() -> files.wasDone(begun.connection().id(), key))) {
                    // The same file again: it was applied before. Out of the inbox, into done, nothing applied.
                    UUID marker = UUID.randomUUID();
                    visit.rename(FilePaths.join(layout.inbox(), candidate.name()),
                            FilePaths.join(layout.done(), FilePaths.processingName(marker, candidate.name())));
                    count("duplicate", FlowReason.FILE_DUPLICATE.key());
                    continue;
                }
                UUID id = inTransaction(() -> files.claim(begun.connection().id(), key, candidate.size()));
                String processing = FilePaths.processingName(id, candidate.name());
                try {
                    visit.rename(FilePaths.join(layout.inbox(), candidate.name()),
                            FilePaths.join(layout.processing(), processing));
                } catch (TransportFailure refused) {
                    if (visit.stat(FilePaths.join(layout.inbox(), candidate.name())).isEmpty()) {
                        // Taken by someone else, or removed: nothing to do for this row.
                        inTransaction(() -> {
                            files.abandon(id, FlowReason.FILE_VANISHED.key());
                            return null;
                        });
                        continue;
                    }
                    throw refused;
                }
                inTransaction(() -> {
                    files.claimed(id);
                    return null;
                });
                processingNames.put(id, processing);
                todo.add(new Todo(id, processing));
                taken++;
            }
            waiting = Math.max(0, candidates.size() - handled);
            more = waiting > 0;
        }

        // Then each file in turn. The read is a short visit of its own, the rows need no visit at all.
        List<Move> moves = new ArrayList<>();
        for (Todo item : todo) {
            if (clock.instant().isAfter(deadline)) {
                more = true;
                break;
            }
            Move move = work(begun, login, target, item, processingNames, deadline);
            if (move != null) {
                moves.add(move);
            }
        }

        // The last visit moves every finished file to where a person expects it, then the ledger says so.
        if (!moves.isEmpty()) {
            try (FileVisit visit = transport.open(target, login)) {
                for (Move move : moves) {
                    finish(begun, visit, move);
                }
            }
        }
        return new Looked(waiting, more);
    }

    /**
     * Takes one file as far as it goes in this look. Returns what to move at the end, or null when the file is not
     * finished (its time ran out, or the connection was switched off) and will be resumed by the next look.
     */
    private Move work(Begun begun, Login login, FileTransport.Target target, Todo item,
            Map<UUID, String> processingNames, Instant deadline) {
        FileLedgerStore.Entry entry = inTransaction(() -> files.find(item.id())).orElse(null);
        if (entry == null) {
            return null;
        }
        String processingName = item.processingName() != null ? item.processingName()
                : processingNames.get(entry.id());
        if (entry.state().equals("APPLIED")) {
            return new Move(entry.id(), processingName, entry.rowsRefused() > 0 ? begun.layout().error()
                    : begun.layout().done(), entry.rowsRefused() > 0 ? FlowReason.FILE_ROWS_REFUSED.key() : null);
        }
        if (entry.state().equals("REJECTED")) {
            return new Move(entry.id(), processingName, begun.layout().error(), entry.reason());
        }
        // CLAIMED: apply what is left.
        if (processingName == null) {
            return reject(begun, entry, null, FlowReason.FILE_VANISHED.key());
        }
        if (entry.sizeBytes() > settings.maxFileBytes()) {
            return reject(begun, entry, processingName, FlowReason.FILE_TOO_LARGE.key());
        }
        byte[] content;
        try (FileVisit visit = transport.open(target, login)) {
            content = visit.read(FilePaths.join(begun.layout().processing(), processingName), settings.maxFileBytes());
        } catch (TransportFailure failure) {
            if (failure.reason() == TestReason.RESPONSE_TOO_LARGE) {
                return reject(begun, entry, processingName, FlowReason.FILE_TOO_LARGE.key());
            }
            throw failure;
        }
        String hash = FilePaths.sha256(content);
        if (entry.contentHash() != null && !entry.contentHash().equals(hash)) {
            return reject(begun, entry, processingName, FlowReason.FILE_UNREADABLE.key());
        }
        List<CanonicalEntity> rows;
        try {
            rows = begun.connector().read(begun.connection().definition().settings(), content, settings.maxRows());
        } catch (FileFormatException unreadable) {
            return reject(begun, entry, processingName, unreadable.problem().key());
        }
        if (entry.rowsTotal() == null) {
            for (CanonicalEntity row : rows) {
                String problem = inbound.mappingProblem(begun.mapping(), row);
                if (problem != null) {
                    return reject(begun, entry, processingName, problem);
                }
            }
            inTransaction(() -> {
                files.read(entry.id(), hash, rows.size());
                return null;
            });
        } else if (entry.rowsTotal() != rows.size()) {
            return reject(begun, entry, processingName, FlowReason.FILE_UNREADABLE.key());
        }
        int total = rows.size();
        int done = entry.rowsDone();
        while (true) {
            Batch batch = FileBatchPlan.next(total, done, settings.batchRows());
            if (batch == null) {
                break;
            }
            if (clock.instant().isAfter(deadline)) {
                // Out of time: what is written stays written, and the next look resumes at the next row.
                return null;
            }
            if (!applyBatch(begun, entry.id(), rows, batch)) {
                // The connection was switched off in the middle (the rest is resumed when it is on), or the ledger
                // moved under us (it cannot, with the lease): stop without writing anything more.
                return null;
            }
            done = batch.to();
        }
        inTransaction(() -> {
            files.applied(entry.id());
            return null;
        });
        FileLedgerStore.Entry after = inTransaction(() -> files.find(entry.id())).orElse(entry);
        boolean refused = after.rowsRefused() > 0;
        return new Move(entry.id(), processingName, refused ? begun.layout().error() : begun.layout().done(),
                refused ? FlowReason.FILE_ROWS_REFUSED.key() : null);
    }

    /** One batch in one transaction, with its progress. False when the connection is unusable or the ledger moved. */
    private boolean applyBatch(Begun begun, UUID id, List<CanonicalEntity> rows, Batch batch) {
        return transaction.execute(status -> {
            Connection fresh = connections.findById(begun.connection().id()).orElse(null);
            if (fresh == null || !ConnectionMachine.isUsable(fresh.state())) {
                return false;
            }
            int applied = 0;
            int skipped = 0;
            int refused = 0;
            for (CanonicalEntity row : rows.subList(batch.from(), batch.to())) {
                Outcome outcome = inbound.applyEntity(fresh, begun.live(), begun.mapping(), row, UUID.randomUUID());
                switch (outcome.state()) {
                    case "DONE" -> applied++;
                    case "SKIPPED" -> skipped++;
                    default -> refused++;
                }
            }
            if (!files.progress(id, batch.from(), batch.size(), applied, skipped, refused)) {
                status.setRollbackOnly();
                return false;
            }
            meters.counter("platform.integration.files.rows", "result", "applied").increment(applied);
            meters.counter("platform.integration.files.rows", "result", "skipped").increment(skipped);
            meters.counter("platform.integration.files.rows", "result", "refused").increment(refused);
            return true;
        });
    }

    /** The whole file failed: nothing of it was applied. The ledger says so; the file goes to the error directory. */
    private Move reject(Begun begun, FileLedgerStore.Entry entry, String processingName, String reason) {
        inTransaction(() -> {
            files.rejected(entry.id(), reason);
            return null;
        });
        return new Move(entry.id(), processingName, begun.layout().error(), reason);
    }

    /** Moves a finished file on the server, then records where it is. A file already gone is not an error. */
    private void finish(Begun begun, FileVisit visit, Move move) {
        if (move.processingName() != null) {
            String from = FilePaths.join(begun.layout().processing(), move.processingName());
            String to = FilePaths.join(move.toDirectory(), move.processingName());
            if (visit.stat(from).isPresent()) {
                visit.rename(from, to);
            }
        }
        boolean aside = move.toDirectory().equals(begun.layout().error());
        FileLedgerStore.Entry entry = inTransaction(() -> files.find(move.id())).orElseThrow();
        transaction.executeWithoutResult(status -> {
            if (aside) {
                files.setAside(move.id(), move.reason() == null ? FlowReason.FILE_UNREADABLE.key() : move.reason());
            } else {
                files.done(move.id());
            }
            audit.record(AuditRecord.of("integration.file.processed", aside ? AuditOutcome.FAILURE
                    : AuditOutcome.SUCCESS).forUser(ActorId.SYSTEM.value()).inTenant(contexts.require().tenantId())
                    .with("integration", begun.connection().id().toString()).with("file", move.id().toString())
                    .with("result", aside ? "set_aside" : "done")
                    .with("reason", move.reason() == null ? DEFAULT_RESULT : move.reason())
                    .with("rows", Integer.toString(entry.rowsTotal() == null ? 0 : entry.rowsTotal()))
                    .with("applied", Integer.toString(entry.rowsApplied()))
                    .with("skipped", Integer.toString(entry.rowsSkipped()))
                    .with("refused", Integer.toString(entry.rowsRefused())));
        });
        meters.counter("platform.integration.files", "result", aside ? "set_aside" : "done",
                "reason", move.reason() == null ? DEFAULT_RESULT : move.reason()).increment();
    }

    // ---- the end of a look ----

    private void conclude(PollStore.Claimed claim, Begun begun, TestReason failure, Looked looked) {
        transaction.executeWithoutResult(status -> end(claim, begun, failure, looked));
    }

    private void end(PollStore.Claimed claim, Begun begun, TestReason failure, Looked looked) {
        Instant now = clock.instant();
        UUID connection = begun.connection().id();
        if (failure == null) {
            gate.report(connection, true, RetryPolicy.Health.ALIVE, null, now);
            Instant next = looked.more() ? now : now.plus(longer(begun.layout().pollEvery(), settings.minInterval()));
            polls.finish(claim.id(), next, "LOOKED", null, looked.waiting());
            files.purge(connection, settings.ledgerRetention(), 100);
            count("looked", DEFAULT_RESULT);
            return;
        }
        RetryPolicy.Verdict verdict = RetryPolicy.failure(failure);
        gate.report(connection, false, verdict.health(), verdict.reason(), now);
        if (ConnectionFailures.stops(failure)) {
            failures.stopUsing(connection, failure);
        }
        // A failure that waiting may mend is looked at again soon (the breaker counts it); one that waiting cannot mend
        // (a missing credential, a missing directory) is looked at again on the connection's own schedule.
        Instant next = verdict.retry()
                ? now.plus(RetryPolicy.delay(reliability.retry(), 1, ThreadLocalRandom.current().nextDouble(), null))
                : now.plus(longer(begun.layout().pollEvery(), settings.minInterval()));
        polls.finish(claim.id(), next, "FAILED", verdict.reason(), begun.previousWaiting());
        count("failed", verdict.reason());
    }

    private static java.time.Duration longer(java.time.Duration a, java.time.Duration b) {
        return a.compareTo(b) >= 0 ? a : b;
    }

    private <T> T inTransaction(Supplier<T> work) {
        return transaction.execute(status -> work.get());
    }

    private void count(String result, String reason) {
        meters.counter("platform.integration.files.polls", "result", result, "reason", reason).increment();
    }

    /** Whether the instance may run this class at all; the worker asks before it claims a poll. */
    boolean enabled() {
        return settings.enabled();
    }

    /** The most polls one instance runs at once. */
    int concurrency() {
        return settings.maxConcurrentPolls();
    }

    /** The lease of a claimed poll. */
    java.time.Duration lease() {
        return settings.lease();
    }
}
