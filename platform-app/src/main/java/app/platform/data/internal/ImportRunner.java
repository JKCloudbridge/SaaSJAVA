package app.platform.data.internal;

import app.platform.metadata.Metadata;
import app.platform.metadata.ObjectDefinition;
import app.platform.security.Decisions;
import app.platform.security.ObjectAccess;
import app.platform.sharedkernel.ActorId;
import app.platform.sharedkernel.TenantId;
import app.platform.sharedkernel.directory.ReferenceDirectory;
import app.platform.sharedkernel.storage.ObjectStore;
import app.platform.sharedkernel.storage.ObjectStoreException;
import app.platform.tenant.Tenant;
import app.platform.tenant.TenantContext;
import app.platform.tenant.TenantContexts;
import app.platform.tenant.Tenants;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs the imports (Sprint 17, ADR-0123): reads the file a person sent, a batch of rows at a time, and writes the
 * records through {@link ImportRows} with the permissions, and in the name, of the member who started the import.
 *
 * <p><strong>Resumable.</strong> The job row says where the work stands: the byte offset after the last row that was
 * committed. A batch is one transaction that writes its records, its failed rows and the new offset together, so a
 * crash loses nothing and repeats nothing: the next claim carries on from the stored offset. A job is claimed with a
 * lease that every batch renews; a job whose lease has expired (the instance died) is taken over by the sweep. One
 * import of an object runs at a time (a database constraint), and an organization has a few at most.
 *
 * <p><strong>Acting for the member.</strong> Each batch runs in a tenant context that names the member, so the gate,
 * the permissions on the object and its fields, the pipeline and the record-level security that applies to a write are
 * exactly those of a person writing the same record, and the records are theirs. If the member leaves the organization
 * or the permission is taken away meanwhile, the import stops (a member who left) or its rows are refused (a permission
 * that went), and the report says so. A member who is deactivated never has a job that carries on in their name.
 *
 * <p>A <strong>dry run</strong> reads the file the same way but writes it all in one transaction that is rolled back,
 * so rows that depend on earlier rows see them, and nothing remains; it is bounded in rows for that reason.
 *
 * <p>Started right after a file arrives and by a sweep over all organizations at an interval, which also repairs a
 * missed start. The files and the report are removed after the retention period. No cell of a file, no column name and
 * no reason is logged.
 */
@Component
class ImportRunner implements SmartLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(ImportRunner.class);
    private static final int TENANT_PAGE = 200;
    private static final int RUNNABLE_PER_PASS = 3;
    private static final int RAW_ROW_LENGTH = 4000;
    private static final int REPORT_PAGE = 500;
    private static final long REPORT_LIMIT_BYTES = 32L * 1024 * 1024;
    private static final TypeReference<Map<String, String>> MAPPING = new TypeReference<>() { };
    private static final ActorId SYSTEM = ActorId.SYSTEM;

    private final Tenants tenants;
    private final TenantContexts contexts;
    private final ImportStore store;
    private final ObjectStore objects;
    private final ImportRows rows;
    private final Metadata metadata;
    private final Decisions decisions;
    private final Map<String, ReferenceDirectory> directories = new HashMap<>();
    private final RecordChanges changes;
    private final TransactionTemplate transaction;
    private final ImportProperties settings;
    private final JsonMapper json = JsonMapper.builder().build();
    private final Set<TenantId> requested = ConcurrentHashMap.newKeySet();
    private ScheduledExecutorService executor;

    ImportRunner(Tenants tenants, TenantContexts contexts, ImportStore store, ObjectStore objects, ImportRows rows,
            Metadata metadata, Decisions decisions, List<ReferenceDirectory> directories, RecordChanges changes,
            TransactionTemplate transaction, ImportProperties settings) {
        this.tenants = tenants;
        this.contexts = contexts;
        this.store = store;
        this.objects = objects;
        this.rows = rows;
        this.metadata = metadata;
        this.decisions = decisions;
        directories.forEach(directory -> this.directories.put(directory.object(), directory));
        this.changes = changes;
        this.transaction = transaction;
        this.settings = settings;
    }

    // ---- scheduling ----

    /**
     * One sweep over every organization. Public to the package so a test can run it without waiting.
     *
     * @return how many jobs were taken
     */
    int runOnce() {
        int taken = 0;
        String after = null;
        while (true) {
            List<Tenant> page = tenants.list(after, null, TENANT_PAGE);
            for (Tenant tenant : page) {
                taken += workOn(tenant.id());
            }
            if (page.size() < TENANT_PAGE) {
                break;
            }
            after = page.get(page.size() - 1).slug().value();
        }
        return taken;
    }

    /** Asks for the organization's jobs to be run soon, after the transaction the caller is in has committed. */
    void request(TenantId tenant) {
        if (executor == null) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    schedule(tenant);
                }
            });
        } else {
            schedule(tenant);
        }
    }

    private void schedule(TenantId tenant) {
        if (requested.add(tenant)) {
            executor.execute(() -> {
                requested.remove(tenant);
                try {
                    workOn(tenant);
                } catch (RuntimeException e) {
                    LOG.warn("Import work failed ({})", e.getClass().getSimpleName());
                }
            });
        }
    }

    /**
     * The work of one organization: take the jobs that can run, run them, remove old files. Returns how many jobs were
     * taken.
     */
    int workOn(TenantId tenantId) {
        if (!objects.configured()) {
            return 0;
        }
        try {
            return contexts.call(TenantContext.of(tenantId), () -> {
                int taken = 0;
                List<UUID> ids = transaction.execute(status -> store.runnable(RUNNABLE_PER_PASS));
                for (UUID id : ids == null ? List.<UUID>of() : ids) {
                    Boolean claimed = transaction.execute(status -> store.claim(id, settings.lease(), SYSTEM.value()));
                    if (Boolean.TRUE.equals(claimed)) {
                        taken++;
                        run(tenantId, id);
                    }
                }
                forgetOldFiles();
                return taken;
            });
        } catch (RuntimeException e) {
            LOG.warn("Import work for an organization failed ({})", e.getClass().getSimpleName());
            return 0;
        }
    }

    // ---- one job ----

    private void run(TenantId tenantId, UUID id) {
        ImportStore.JobRow job = transaction.execute(status -> store.job(id).orElse(null));
        if (job == null) {
            return;
        }
        TenantContext acting = new TenantContext(tenantId, job.startedBy(), job.membershipId());
        try {
            contexts.run(acting, () -> execute(job));
        } catch (RuntimeException e) {
            LOG.warn("An import stopped because of an unexpected error ({})", e.getClass().getSimpleName());
            fail(job, "The import stopped because of an error on our side. Nothing more was written; try again.");
        }
    }

    private void execute(ImportStore.JobRow job) {
        try {
            ObjectDefinition object = metadata.object(job.object()).filter(found -> found.managedBy() == null)
                    .orElse(null);
            if (object == null) {
                fail(job, "The object no longer exists.");
                return;
            }
            // Its own transaction: starterIsMember reads a forced-row-level-security table, and only a transaction
            // puts the tenant into the database session (TenantSessionListener); a plain call outside one would see
            // no rows at all and always answer "no longer a member".
            if (!Boolean.TRUE.equals(transaction.execute(status -> starterIsMember(job)))) {
                fail(job, "The person who started the import is no longer a member of the organization.");
                return;
            }
            char delimiter = delimiter(job.delimiter());
            Map<String, String> mapping = json.readValue(job.mapping(), MAPPING);
            // Its own transaction too: on a cache miss (or the cache switched off) this reads forced-row-level
            // security tables, which needs the same tenant session as starterIsMember above.
            ObjectAccess access = transaction.execute(status -> decisions.accessTo(job.membershipId(),
                    object.apiName()));
            List<String> header;
            long headerEnd;
            try (ObjectStore.Stored stored = open(job, 0)) {
                CsvReader.Row first = new CsvReader(stored.bytes(), 0, delimiter).next();
                if (first == null || first.problem() != null) {
                    fail(job, "The file has no header row that can be read.");
                    return;
                }
                header = first.values();
                headerEnd = first.endOffset();
            }
            ImportRows.Plan plan = rows.plan(object, access, job.operation(), job.externalIdField(), mapping, header);
            ImportStore.JobRow checked = job;
            if (!job.checked()) {
                int total = countRows(job, headerEnd, delimiter);
                if (total > settings.maxRows()) {
                    fail(job, "The file has more rows than an import can take (" + settings.maxRows() + ").");
                    return;
                }
                if (job.dryRun() && total > settings.dryRunMaxRows()) {
                    fail(job, "A dry run reads at most " + settings.dryRunMaxRows() + " rows. Split the file, or "
                            + "run the import for real.");
                    return;
                }
                transaction.executeWithoutResult(status -> store.checked(job.id(), total, SYSTEM.value()));
                checked = transaction.execute(status -> store.job(job.id()).orElseThrow());
            }
            if (checked.dryRun()) {
                dryRun(checked, plan, headerEnd, delimiter, header);
            } else {
                live(checked, plan, headerEnd, delimiter, header);
            }
        } catch (ImportRows.Problems problems) {
            fail(job, String.join(" ", problems.found()));
        } catch (CsvReader.CsvFormatException e) {
            fail(job, e.getMessage());
        } catch (ObjectStoreException e) {
            // The store did not answer. The job stays claimed; its lease runs out and the sweep tries again.
            LOG.warn("The object store did not answer while an import was running; it will be tried again");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void live(ImportStore.JobRow job, ImportRows.Plan plan, long headerEnd, char delimiter,
            List<String> header) throws IOException {
        long cursor = Math.max(job.cursorOffset(), headerEnd);
        int rowBase = job.processedRows();
        while (true) {
            long from = cursor;
            int base = rowBase;
            Batch batch = transaction.execute(status -> batch(job, plan, delimiter, from, base));
            if (batch == null) {
                return;
            }
            switch (batch.end()) {
                case CANCELLED -> {
                    finish(job, "CANCELLED", null, header);
                    return;
                }
                case MEMBER_GONE -> {
                    fail(job, "The person who started the import is no longer a member of the organization.");
                    return;
                }
                case FINISHED -> {
                    finish(job, "COMPLETED", null, header);
                    return;
                }
                default -> {
                    cursor = batch.endOffset();
                    rowBase += batch.rows();
                }
            }
        }
    }

    /** The batch kinds: more to do, or why the loop ends. */
    private enum End { MORE, FINISHED, CANCELLED, MEMBER_GONE }

    private record Batch(End end, int rows, long endOffset) {
    }

    /** Runs inside a transaction, as the member. */
    private Batch batch(ImportStore.JobRow job, ImportRows.Plan plan, char delimiter, long from, int rowBase) {
        ImportStore.JobRow fresh = store.job(job.id()).orElseThrow();
        if (fresh.cancelRequested()) {
            return new Batch(End.CANCELLED, 0, from);
        }
        if (!starterIsMember(job)) {
            return new Batch(End.MEMBER_GONE, 0, from);
        }
        int[] counts = new int[4];
        int recorded = store.errorCount(job.id());
        long end = from;
        boolean eof = false;
        try (ObjectStore.Stored stored = open(job, from)) {
            CsvReader reader = new CsvReader(stored.bytes(), from, delimiter);
            while (counts[0] < settings.batchRows()) {
                CsvReader.Row row = reader.next();
                if (row == null) {
                    eof = true;
                    break;
                }
                counts[0]++;
                end = row.endOffset();
                ImportRows.Outcome outcome = row.problem() != null
                        ? new ImportRows.Outcome(ImportRows.Kind.FAILED, row.problem())
                        : rows.process(plan, row.values());
                switch (outcome.kind()) {
                    case CREATED -> counts[1]++;
                    case UPDATED -> counts[2]++;
                    default -> {
                        counts[3]++;
                        if (recorded < settings.maxErrors()) {
                            store.addError(job.id(), rowBase + counts[0], outcome.reason(), raw(row), SYSTEM.value());
                            recorded++;
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        store.progress(job.id(), counts[0], counts[1], counts[2], counts[3], end, settings.lease(), SYSTEM.value());
        return new Batch(eof ? End.FINISHED : End.MORE, counts[0], end);
    }

    private void dryRun(ImportStore.JobRow job, ImportRows.Plan plan, long headerEnd, char delimiter,
            List<String> header) throws IOException {
        int[] counts = new int[4];
        List<ImportStore.ErrorRow> errors = new ArrayList<>();
        transaction.executeWithoutResult(status -> {
            try (ObjectStore.Stored stored = open(job, headerEnd)) {
                CsvReader reader = new CsvReader(stored.bytes(), headerEnd, delimiter);
                CsvReader.Row row;
                while ((row = reader.next()) != null) {
                    counts[0]++;
                    ImportRows.Outcome outcome = row.problem() != null
                            ? new ImportRows.Outcome(ImportRows.Kind.FAILED, row.problem())
                            : rows.process(plan, row.values());
                    switch (outcome.kind()) {
                        case CREATED -> counts[1]++;
                        case UPDATED -> counts[2]++;
                        default -> {
                            counts[3]++;
                            if (errors.size() < settings.maxErrors()) {
                                errors.add(new ImportStore.ErrorRow(counts[0], outcome.reason(), raw(row)));
                            }
                        }
                    }
                    if (counts[0] % settings.batchRows() == 0) {
                        store.renew(job.id(), settings.lease(), SYSTEM.value());
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            // Nothing a dry run wrote stays: the transaction ends by being rolled back.
            status.setRollbackOnly();
        });
        transaction.executeWithoutResult(status -> {
            errors.forEach(error -> store.addError(job.id(), error.rowNumber(), error.reason(), error.rawRow(),
                    SYSTEM.value()));
            store.progress(job.id(), counts[0], counts[1], counts[2], counts[3], 0, settings.lease(), SYSTEM.value());
        });
        finish(job, "COMPLETED", null, header);
    }

    // ---- the file ----

    private int countRows(ImportStore.JobRow job, long headerEnd, char delimiter) throws IOException {
        int total = 0;
        try (ObjectStore.Stored stored = open(job, headerEnd)) {
            CsvReader reader = new CsvReader(stored.bytes(), headerEnd, delimiter);
            while (reader.next() != null) {
                total++;
                if (total > settings.maxRows() + 1) {
                    break;
                }
            }
        }
        return total;
    }

    private ObjectStore.Stored open(ImportStore.JobRow job, long offset) {
        ObjectStore.Stored stored = objects.get(job.sourceKey(), offset);
        if (stored == null) {
            throw new CsvReader.CsvFormatException("The file of the import is no longer there.");
        }
        return stored;
    }

    private static String raw(CsvReader.Row row) {
        if (row.values().isEmpty()) {
            return "";
        }
        String line = CsvWriter.line(row.values());
        return line.length() <= RAW_ROW_LENGTH ? line : line.substring(0, RAW_ROW_LENGTH);
    }

    private static char delimiter(String name) {
        return switch (name) {
            case "SEMICOLON" -> ';';
            case "TAB" -> '\t';
            default -> ',';
        };
    }

    // ---- the end ----

    /** Ends the job in a state, with the report of the rows that failed when there is one. */
    private void finish(ImportStore.JobRow job, String state, String failure, List<String> header) {
        String errorKey = null;
        ImportStore.JobRow now = transaction.execute(status -> store.job(job.id()).orElseThrow());
        if (now.failedRows() > 0 && transaction.execute(status -> store.errorCount(job.id())) > 0) {
            errorKey = writeReport(now, header);
        }
        String key = errorKey;
        transaction.executeWithoutResult(status -> {
            store.finish(job.id(), state, failure, key, settings.retention(), SYSTEM.value());
            changes.importFinished(job.startedBy(), job.object(), job.id(), job.operation(), job.dryRun(), state,
                    now.processedRows(), now.createdRows(), now.updatedRows(), now.failedRows());
        });
    }

    private void fail(ImportStore.JobRow job, String reason) {
        String bounded = reason.length() <= 1900 ? reason : reason.substring(0, 1900);
        transaction.executeWithoutResult(status -> {
            ImportStore.JobRow now = store.job(job.id()).orElse(job);
            store.finish(job.id(), "FAILED", bounded, null, settings.retention(), SYSTEM.value());
            changes.importFinished(job.startedBy(), job.object(), job.id(), job.operation(), job.dryRun(), "FAILED",
                    now.processedRows(), now.createdRows(), now.updatedRows(), now.failedRows());
        });
    }

    /** The report of the rows that failed, as a file: the row number, the reason, and the row as it was. */
    private String writeReport(ImportStore.JobRow job, List<String> header) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        List<String> head = new ArrayList<>(List.of("row_number", "reason"));
        head.addAll(header);
        bytes.writeBytes((CsvWriter.line(head) + "\r\n").getBytes(StandardCharsets.UTF_8));
        int after = 0;
        while (bytes.size() < REPORT_LIMIT_BYTES) {
            int last = after;
            List<ImportStore.ErrorRow> page = transaction.execute(status -> store.errors(job.id(), last, REPORT_PAGE));
            if (page == null || page.isEmpty()) {
                break;
            }
            for (ImportStore.ErrorRow error : page) {
                bytes.writeBytes((CsvWriter.line(List.of(Integer.toString(error.rowNumber()), error.reason()))
                        + (error.rawRow().isEmpty() ? "" : "," + error.rawRow()) + "\r\n")
                        .getBytes(StandardCharsets.UTF_8));
                after = error.rowNumber();
            }
        }
        String key = "imports/" + contexts.require().tenantId().value() + "/" + job.id() + "/errors";
        byte[] content = bytes.toByteArray();
        try (InputStream in = new ByteArrayInputStream(content)) {
            objects.put(key, in, content.length, "text/csv");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return key;
    }

    /** Removes the files of the jobs that ended long enough ago. */
    private void forgetOldFiles() {
        List<ImportStore.JobRow> old = transaction.execute(status -> store.expired(20));
        for (ImportStore.JobRow job : old == null ? List.<ImportStore.JobRow>of() : old) {
            try {
                if (job.sourceKey() != null) {
                    objects.delete(job.sourceKey());
                }
                if (job.errorKey() != null) {
                    objects.delete(job.errorKey());
                }
            } catch (ObjectStoreException e) {
                LOG.warn("The object store did not remove the files of an import; it will be tried again");
                return;
            }
            transaction.executeWithoutResult(status -> store.forgetFiles(job.id(), SYSTEM.value()));
        }
    }

    private boolean starterIsMember(ImportStore.JobRow job) {
        ReferenceDirectory users = directories.get("User");
        return users != null && users.exists(job.startedBy());
    }

    // ---- life cycle ----

    @Override
    public void start() {
        if (!settings.enabled() || executor != null) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> new Thread(runnable, "import-runner"));
        long period = settings.sweepInterval().toMillis();
        executor.scheduleWithFixedDelay(() -> {
            try {
                runOnce();
            } catch (RuntimeException e) {
                LOG.warn("The import sweep failed ({})", e.getClass().getSimpleName());
            }
        }, period, period, TimeUnit.MILLISECONDS);
    }

    @Override
    public void stop() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    @Override
    public boolean isRunning() {
        return executor != null;
    }
}
