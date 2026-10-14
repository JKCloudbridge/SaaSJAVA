package app.platform.data.internal;

import app.platform.data.StorageStrategy;
import app.platform.data.UniqueValueTakenException;
import app.platform.sharedkernel.ActorId;
import app.platform.sharedkernel.TenantId;
import app.platform.sharedkernel.audit.AuditOutcome;
import app.platform.sharedkernel.audit.AuditRecord;
import app.platform.sharedkernel.audit.AuditRecorder;
import app.platform.tenant.Tenant;
import app.platform.tenant.TenantContext;
import app.platform.tenant.TenantContexts;
import app.platform.tenant.Tenants;
import java.util.List;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Fills the typed copy of a newly indexed field for the records that already exist, and empties it when a field stops
 * being indexed (Sprint 16, ADR-0101).
 *
 * <p><strong>Resumable and bounded.</strong> The work is done in batches of {@code batch-size} records, each in its
 * own transaction and in the organization's own tenant context (row level security stays on, no system scope is
 * needed, and an organization that fails does not hold up the others), at most {@code batches-per-pass} batches per
 * field per pass. Where a batch stopped is stored in {@code field_slot}, so a restart, a crash or another instance
 * carries on from there. One batch of an object runs at a time (a lock taken for the batch): two instances share the
 * work and never do the same batch, and a batch is repeatable anyway (it only writes a copy that differs).
 *
 * <p>A copy is called complete only after a check over the whole object found nothing to repair. The fill does not
 * change the version or the time of last change of a record (the database lets a change of the copies alone keep them,
 * ADR-0102). One audit record per field and pass says how many records it looked at; never a value.
 *
 * <p>Started by the event of a published change (right after the change committed) and by a sweep over every
 * organization at the configured interval, which also repairs a missed event.
 */
@Component
class IndexBackfill implements SmartLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(IndexBackfill.class);
    private static final int TENANT_PAGE = 200;

    /** What one batch did. */
    private enum Step { MORE, DONE, BUSY }

    private final Tenants tenants;
    private final TenantContexts contexts;
    private final FieldSlotStore store;
    private final UniqueStore uniques;
    private final StorageStrategy storage;
    private final IndexPolicy policy;
    private final LinkBackfill linkFill;
    private final SearchBackfill searchFill;
    private final AuditRecorder audit;
    private final TransactionTemplate transaction;
    private final RecordProperties.Index settings;
    private final Set<TenantId> requested = ConcurrentHashMap.newKeySet();
    private ScheduledExecutorService executor;

    IndexBackfill(Tenants tenants, TenantContexts contexts, FieldSlotStore store, UniqueStore uniques,
            StorageStrategy storage, IndexPolicy policy, LinkBackfill linkFill, SearchBackfill searchFill,
            AuditRecorder audit, PlatformTransactionManager transactions, RecordProperties properties) {
        this.tenants = tenants;
        this.contexts = contexts;
        this.store = store;
        this.uniques = uniques;
        this.storage = storage;
        this.policy = policy;
        this.linkFill = linkFill;
        this.searchFill = searchFill;
        this.audit = audit;
        this.transaction = new TransactionTemplate(transactions);
        this.settings = properties.index();
    }

    /**
     * One sweep over every organization. Public to the package so a test can run it without waiting.
     *
     * @return how many batches were run
     */
    int runOnce() {
        int batches = 0;
        String after = null;
        while (true) {
            List<Tenant> page = tenants.list(after, null, TENANT_PAGE);
            for (Tenant tenant : page) {
                batches += workOn(tenant.id());
            }
            if (page.size() < TENANT_PAGE) {
                break;
            }
            after = page.get(page.size() - 1).slug().value();
        }
        return batches;
    }

    /**
     * Asks for the organization's work to be done soon, after the transaction the caller is in has committed (the work
     * must see what that transaction changed). Without a transaction, at once.
     */
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
                    LOG.warn("Indexing work failed ({})", e.getClass().getSimpleName());
                }
            });
        }
    }

    /**
     * The work of one organization: bring the assignments in line with the flags, then fill and empty. Returns how many
     * batches ran; an organization that fails is logged by the kind of failure only.
     */
    int workOn(TenantId tenantId) {
        try {
            return contexts.call(TenantContext.of(tenantId), () -> {
                transaction.executeWithoutResult(status -> policy.reconcile());
                List<FieldSlotStore.Row> rows = transaction.execute(status -> store.all());
                int batches = 0;
                for (FieldSlotStore.Row row : rows == null ? List.<FieldSlotStore.Row>of() : rows) {
                    batches += switch (row.state()) {
                        case FILLING -> pass(row, true);
                        case CLEARING -> pass(row, false);
                        case FILLED -> 0;
                    };
                }
                if (batches > 0) {
                    // A copy that has just become complete may start the uniqueness constraint that waits for it.
                    transaction.executeWithoutResult(status -> policy.reconcile());
                }
                List<UniqueStore.Row> constraints = transaction.execute(status -> uniques.all());
                for (UniqueStore.Row row : constraints == null ? List.<UniqueStore.Row>of() : constraints) {
                    batches += switch (row.state()) {
                        case ENFORCING -> enforce(row);
                        case CONFLICT -> recheck(row);
                        case ENFORCED -> 0;
                    };
                }
                return batches + linkFill.fill() + searchFill.fill();
            });
        } catch (RuntimeException e) {
            LOG.warn("Indexing work for an organization failed ({})", e.getClass().getSimpleName());
            return 0;
        }
    }

    /** At most {@code batches-per-pass} batches for one field; one audit record with the count. */
    private int pass(FieldSlotStore.Row row, boolean filling) {
        int batches = 0;
        long records = 0;
        while (batches < settings.batchesPerPass()) {
            long[] result = new long[2];
            Step step = transaction.execute(status -> filling ? fillBatch(row, result) : clearBatch(row, result));
            if (step == Step.BUSY) {
                break;
            }
            batches++;
            records += result[0];
            if (step == Step.DONE) {
                break;
            }
        }
        if (batches > 0) {
            long total = records;
            int count = batches;
            transaction.executeWithoutResult(status -> audit.record(AuditRecord.of(
                    filling ? "data.index.fill.pass" : "data.index.clear.pass", AuditOutcome.SUCCESS)
                    .inTenant(contexts.require().tenantId()).onObject(row.objectApiName() + "." + row.fieldApiName(),
                            null)
                    .with("batches", Integer.toString(count)).with("records", Long.toString(total))));
        }
        return batches;
    }

    private Step fillBatch(FieldSlotStore.Row seen, long[] result) {
        if (!store.tryWorkLock(seen.objectApiName())) {
            return Step.BUSY;
        }
        FieldSlotStore.Row row = store.find(seen.id()).orElse(null);
        if (row == null || row.state() != FieldSlotStore.State.FILLING) {
            return Step.DONE;
        }
        UUID actor = ActorId.SYSTEM.value();
        StorageStrategy.Filled filled = storage.fillSlot(row.objectApiName(), row.fieldApiName(),
                row.slot().column(), row.cursor(), settings.batchSize());
        result[0] = filled.processed();
        if (filled.processed() > 0) {
            store.progress(row.id(), filled.lastId(), filled.processed(), actor);
        }
        if (filled.processed() < settings.batchSize()) {
            if (storage.hasUnfilledSlot(row.objectApiName(), row.fieldApiName(), row.slot().column())) {
                // Nothing should be left. If something is, the copy is not trusted: start over (and say so).
                LOG.warn("The copy of an indexed field was not complete after the fill; filling again");
                store.restart(row.id(), actor);
                return Step.MORE;
            }
            if (store.markFilled(row.id(), actor)) {
                audit.record(AuditRecord.of("data.index.filled", AuditOutcome.SUCCESS)
                        .inTenant(contexts.require().tenantId())
                        .onObject(row.objectApiName() + "." + row.fieldApiName(), null)
                        .with("records", Long.toString(row.recordsDone() + filled.processed())));
            }
            return Step.DONE;
        }
        return Step.MORE;
    }

    private Step clearBatch(FieldSlotStore.Row seen, long[] result) {
        if (!store.tryWorkLock(seen.objectApiName())) {
            return Step.BUSY;
        }
        FieldSlotStore.Row row = store.find(seen.id()).orElse(null);
        if (row == null || row.state() != FieldSlotStore.State.CLEARING) {
            return Step.DONE;
        }
        UUID actor = ActorId.SYSTEM.value();
        int cleared = storage.clearSlot(row.objectApiName(), row.slot().column(), settings.batchSize());
        result[0] = cleared;
        if (cleared < settings.batchSize() && !storage.hasSlotValues(row.objectApiName(), row.slot().column())) {
            store.free(row.id(), actor);
            audit.record(AuditRecord.of("data.index.freed", AuditOutcome.SUCCESS)
                    .inTenant(contexts.require().tenantId())
                    .onObject(row.objectApiName() + "." + row.fieldApiName(), null));
            return Step.DONE;
        }
        store.progress(row.id(), null, cleared, actor);
        return Step.MORE;
    }

    // ---- uniqueness (ADR-0111) ----

    /**
     * Switches a constraint on for the records that existed before it: their bit is written in batches, like the fill
     * of a copy. Writes carry the bit and check existing values from the moment the constraint row exists, so the only
     * records left are the old ones. Two records that hold the same value make a batch fail as a whole; the constraint
     * then goes to CONFLICT with the count, and the work resumes when the data was fixed ({@link #recheck}).
     */
    private int enforce(UniqueStore.Row row) {
        int batches = 0;
        long records = 0;
        while (batches < settings.batchesPerPass()) {
            long[] result = new long[1];
            Step step;
            try {
                step = transaction.execute(status -> maskBatch(row, result));
            } catch (UniqueValueTakenException duplicate) {
                conflict(row);
                break;
            }
            if (step == Step.BUSY) {
                break;
            }
            batches++;
            records += result[0];
            if (step == Step.DONE) {
                break;
            }
        }
        if (batches > 0) {
            long total = records;
            int count = batches;
            transaction.executeWithoutResult(status -> audit.record(AuditRecord.of("data.unique.mask.pass",
                    AuditOutcome.SUCCESS).inTenant(contexts.require().tenantId())
                    .onObject(row.objectApiName() + "." + row.fieldApiName(), null)
                    .with("batches", Integer.toString(count)).with("records", Long.toString(total))));
        }
        return batches;
    }

    private Step maskBatch(UniqueStore.Row seen, long[] result) {
        if (!store.tryWorkLock(seen.objectApiName())) {
            return Step.BUSY;
        }
        UniqueStore.Row row = uniques.find(seen.id()).orElse(null);
        if (row == null || row.state() != UniqueStore.State.ENFORCING) {
            return Step.DONE;
        }
        UUID actor = ActorId.SYSTEM.value();
        StorageStrategy.Filled done = storage.maskUnique(row.objectApiName(), row.bit(), row.cursor(),
                settings.batchSize());
        result[0] = done.processed();
        if (done.processed() > 0) {
            uniques.progress(row.id(), done.lastId(), done.processed(), actor);
        }
        if (done.processed() < settings.batchSize()) {
            if (storage.hasUnmasked(row.objectApiName(), row.bit())) {
                // Nothing should be left. If something is, the constraint is not trusted: start over (and say so).
                LOG.warn("A uniqueness constraint was not complete after its pass; starting over");
                uniques.restart(row.id(), actor);
                return Step.MORE;
            }
            if (uniques.markEnforced(row.id(), actor)) {
                audit.record(AuditRecord.of("data.unique.enforced", AuditOutcome.SUCCESS)
                        .inTenant(contexts.require().tenantId())
                        .onObject(row.objectApiName() + "." + row.fieldApiName(), null)
                        .with("records", Long.toString(row.recordsDone() + done.processed())));
            }
            return Step.DONE;
        }
        return Step.MORE;
    }

    /** Records that two records hold the same value: counts them, and says so (a count, never a value). */
    private void conflict(UniqueStore.Row row) {
        transaction.executeWithoutResult(status -> {
            long colliding = storage.collidingRecords(row.objectApiName(), row.bit());
            if (uniques.markConflict(row.id(), colliding, ActorId.SYSTEM.value())) {
                audit.record(AuditRecord.of("data.unique.conflict", AuditOutcome.FAILURE)
                        .inTenant(contexts.require().tenantId())
                        .onObject(row.objectApiName() + "." + row.fieldApiName(), null)
                        .with("records", Long.toString(colliding)));
            }
        });
        LOG.warn("A uniqueness constraint found records with the same value; it waits until the data is fixed");
    }

    /**
     * A constraint in CONFLICT: counts the records that still share a value (one read of the typed copy), and starts
     * the pass again when there are none. The count in the status follows the data.
     */
    private int recheck(UniqueStore.Row row) {
        transaction.executeWithoutResult(status -> {
            if (!store.tryWorkLock(row.objectApiName())) {
                return;
            }
            long colliding = storage.collidingRecords(row.objectApiName(), row.bit());
            UUID actor = ActorId.SYSTEM.value();
            if (colliding == 0) {
                uniques.restart(row.id(), actor);
            } else if (colliding != row.conflicts()) {
                uniques.markConflict(row.id(), colliding, actor);
            }
        });
        return 0;
    }

    @Override
    public void start() {
        if (!settings.enabled() || executor != null) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> new Thread(runnable, "index-backfill"));
        executor.scheduleWithFixedDelay(() -> {
            try {
                runOnce();
            } catch (RuntimeException e) {
                LOG.warn("The indexing sweep failed ({})", e.getClass().getSimpleName());
            }
        }, settings.interval().toMillis(), settings.interval().toMillis(), TimeUnit.MILLISECONDS);
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
