package app.platform.data.internal;

import app.platform.data.DataRecord;
import app.platform.data.QueryFilter;
import app.platform.metadata.Metadata;
import app.platform.metadata.ObjectDefinition;
import app.platform.sharedkernel.ActorId;
import app.platform.sharedkernel.TenantId;
import app.platform.sharedkernel.audit.AuditOutcome;
import app.platform.sharedkernel.audit.AuditRecord;
import app.platform.sharedkernel.audit.AuditRecorder;
import app.platform.tenant.Tenant;
import app.platform.tenant.TenantContext;
import app.platform.tenant.TenantContexts;
import app.platform.tenant.Tenants;
import java.time.Clock;
import java.util.LinkedHashMap;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Brings the stored results of an object's formulas up to date after a formula was edited, added or removed (Sprint
 * 16A, ADR-0113). Editing a formula leaves the results of existing records as they were (ADR-0095); until this pass has
 * finished a filter or an order on a formula field is refused, because it would answer from results that belong to the
 * old formula.
 *
 * <p><strong>The same calculation as a write.</strong> A record's results are worked out by
 * {@link Calculations#formulas}, the stage a create and an update use, from the values the record holds. There is no
 * second implementation of formulas here. Records are read with {@link QueryService#scan}, the query engine's own
 * stages and statement, in identifier order, one bounded batch at a time.
 *
 * <p><strong>No churn.</strong> A record is written only when a result changed, and then without a new version and
 * without a new time of last change (the database lets the recalculation do exactly that, V043): it is not an edit a
 * person made, and it must not break the form somebody has open. It publishes no event per record and writes no audit
 * record per record: one audit record per finished pass says how many records were examined, rewritten and could not be
 * calculated, and nothing else. A record whose formula cannot be calculated from its values (a division by zero) loses
 * the stored result of that formula (a result that no longer belongs to the formula is worse than none) and is counted.
 *
 * <p><strong>Bounded, resumable, shared.</strong> Per organization, in that organization's own tenant context, one
 * transaction per batch of {@code batch-size} records, at most {@code batches-per-pass} batches per object per pass.
 * Where the pass stopped is in {@code formula_recalculation}, so a crash, a restart or another instance carries on from
 * there. One batch of an object runs at a time (a lock taken for the batch); a batch is repeatable.
 *
 * <p><strong>No write is missed.</strong> A write that began before the formula was published may end after the pass
 * looked at its record, with the old results. So the pass ends with a closing look: after a delay longer than the
 * longest write, it examines again every record changed since a little before the first sweep began.
 *
 * <p>Started by the event of a published change, by a manual start, and by a sweep over every organization at the
 * configured interval that also finds an object whose formulas changed without an event reaching anybody.
 */
@Component
class FormulaRecalculation implements SmartLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(FormulaRecalculation.class);
    private static final int TENANT_PAGE = 200;
    private static final long CLOSING_MARGIN_MILLIS = 1000;

    /** What one batch did. */
    private enum Step { MORE, DONE, BUSY, WAITING }

    private final Tenants tenants;
    private final TenantContexts contexts;
    private final Metadata metadata;
    private final FormulaRecalculationStore store;
    private final QueryService queries;
    private final FormulaResults results;
    private final AuditRecorder audit;
    private final Clock clock;
    private final TransactionTemplate transaction;
    private final RecordProperties.Recalculation settings;
    private final Set<TenantId> requested = ConcurrentHashMap.newKeySet();
    private ScheduledExecutorService executor;

    FormulaRecalculation(Tenants tenants, TenantContexts contexts, Metadata metadata,
            FormulaRecalculationStore store, QueryService queries, FormulaResults results,
            AuditRecorder audit, Clock clock, PlatformTransactionManager transactions,
            RecordProperties properties) {
        this.tenants = tenants;
        this.contexts = contexts;
        this.metadata = metadata;
        this.store = store;
        this.queries = queries;
        this.results = results;
        this.audit = audit;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactions);
        this.settings = properties.recalculation();
    }

    // ---- asking for a pass (inside the organization's context and a transaction) ----

    /**
     * Marks every object whose formulas are not the ones its stored results are current for as waiting for a pass. The
     * object is judged by the published metadata alone, so this finds a change whether or not an event arrived.
     *
     * @return how many objects were marked
     */
    int noteChanges(FormulaRecalculationStore.Trigger by) {
        UUID actor = ActorId.SYSTEM.value();
        int marked = 0;
        Map<String, FormulaRecalculationStore.Row> rows = rows();
        for (ObjectDefinition object : metadata.objects()) {
            String stamp = FormulaStamp.of(object);
            if (object.managedBy() != null || stamp.isEmpty()) {
                continue;
            }
            FormulaRecalculationStore.Row row = rows.get(object.apiName());
            if (row == null) {
                store.ensure(object.apiName(), actor);
                row = store.find(object.apiName()).orElseThrow();
            }
            if (row.state() == FormulaRecalculationStore.State.IDLE && !stamp.equals(row.currentStamp())
                    && store.request(row.id(), by, stamp, actor)) {
                marked++;
            }
        }
        return marked;
    }

    /**
     * Asks for a pass over one object whether or not its results are known to be current (an administrator who wants
     * the results checked, for example after records were changed by a path that does not calculate formulas).
     *
     * @return false when a pass is already waiting or running (it will do the work)
     */
    boolean startNow(ObjectDefinition object) {
        UUID actor = ActorId.SYSTEM.value();
        store.ensure(object.apiName(), actor);
        FormulaRecalculationStore.Row row = store.find(object.apiName()).orElseThrow();
        boolean asked = row.state() == FormulaRecalculationStore.State.IDLE
                && store.request(row.id(), FormulaRecalculationStore.Trigger.MANUAL, FormulaStamp.of(object), actor);
        request(contexts.require().tenantId());
        return asked;
    }

    private Map<String, FormulaRecalculationStore.Row> rows() {
        Map<String, FormulaRecalculationStore.Row> rows = new LinkedHashMap<>();
        store.all().forEach(row -> rows.put(row.objectApiName(), row));
        return rows;
    }

    // ---- the work ----

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
                    schedule(tenant, 0);
                }
            });
        } else {
            schedule(tenant, 0);
        }
    }

    /**
     * Runs the organization's work on the executor after the delay. A run that is asked for at once and has not started
     * yet is enough for any number of such requests; a run that waits for the closing look is not shared with them (it
     * would hold back a pass that was just asked for).
     */
    private void schedule(TenantId tenant, long delayMillis) {
        if (executor == null || (delayMillis == 0 && !requested.add(tenant))) {
            return;
        }
        executor.schedule(() -> {
            if (delayMillis == 0) {
                requested.remove(tenant);
            }
            try {
                workOn(tenant);
            } catch (RuntimeException e) {
                LOG.warn("Formula recalculation failed ({})", e.getClass().getSimpleName());
            }
        }, delayMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * The work of one organization: find the objects that need a pass, then run their passes. Returns how many batches
     * ran; an organization that fails is logged by the kind of failure only.
     */
    int workOn(TenantId tenantId) {
        try {
            return contexts.call(TenantContext.of(tenantId), () -> {
                transaction.executeWithoutResult(status -> noteChanges(FormulaRecalculationStore.Trigger.RESUME));
                List<FormulaRecalculationStore.Row> rows = transaction.execute(status -> store.all());
                int batches = 0;
                long[] comeBack = {-1};
                for (FormulaRecalculationStore.Row row : rows == null ? List.<FormulaRecalculationStore.Row>of()
                        : rows) {
                    if (row.state() != FormulaRecalculationStore.State.IDLE || stale(row)) {
                        batches += pass(row.objectApiName(), comeBack);
                    }
                }
                if (comeBack[0] >= 0) {
                    // More work is due (the pass reached its bound, or the closing look is due after its delay): come
                    // back then, not at the next sweep.
                    schedule(tenantId, comeBack[0]);
                }
                return batches;
            });
        } catch (RuntimeException e) {
            LOG.warn("Formula recalculation for an organization failed ({})", e.getClass().getSimpleName());
            return 0;
        }
    }

    /** Whether the object's row is at rest but does not match its formulas (the batch decides what to do). */
    private boolean stale(FormulaRecalculationStore.Row row) {
        return metadata.object(row.objectApiName()).map(object -> !FormulaStamp.of(object)
                .equals(row.currentStamp() == null ? FormulaStamp.NONE : row.currentStamp())).orElse(false);
    }

    /**
     * At most {@code batches-per-pass} batches for one object. Says through {@code comeBack} when more work is due:
     * at once when the bound was reached with records left, after the delay when the closing look is waiting.
     */
    private int pass(String objectApiName, long[] comeBack) {
        int batches = 0;
        while (batches < settings.batchesPerPass()) {
            Step step = transaction.execute(status -> batch(objectApiName));
            if (step == Step.BUSY || step == null) {
                break;
            }
            batches++;
            if (step == Step.WAITING) {
                long later = settings.closingDelay().toMillis() + CLOSING_MARGIN_MILLIS;
                comeBack[0] = comeBack[0] < 0 ? later : Math.min(comeBack[0], later);
                break;
            }
            if (step == Step.DONE) {
                break;
            }
            if (batches == settings.batchesPerPass()) {
                comeBack[0] = 0;
            }
        }
        return batches;
    }

    private Step batch(String objectApiName) {
        if (!store.tryLock(objectApiName)) {
            return Step.BUSY;
        }
        ObjectDefinition object = metadata.object(objectApiName).filter(found -> found.managedBy() == null)
                .orElse(null);
        FormulaRecalculationStore.Row row = store.find(objectApiName).orElse(null);
        if (object == null || row == null) {
            return Step.DONE;
        }
        UUID actor = ActorId.SYSTEM.value();
        String stamp = FormulaStamp.of(object);
        if (stamp.isEmpty()) {
            if (row.state() != FormulaRecalculationStore.State.IDLE || !FormulaStamp.NONE.equals(row.currentStamp())) {
                store.noFormulas(row.id(), actor);
            }
            return Step.DONE;
        }
        return switch (row.state()) {
            case IDLE -> {
                if (stamp.equals(row.currentStamp())) {
                    yield Step.DONE;
                }
                store.request(row.id(), FormulaRecalculationStore.Trigger.RESUME, stamp, actor);
                yield Step.MORE;
            }
            case PENDING -> {
                store.begin(row.id(), stamp, actor);
                yield Step.MORE;
            }
            case RUNNING -> {
                if (!stamp.equals(row.targetStamp())) {
                    // The formulas were edited again while the pass ran: what it has done belongs to the old ones.
                    store.begin(row.id(), stamp, actor);
                    yield Step.MORE;
                }
                yield sweep(object, row, false, actor);
            }
            case CLOSING -> {
                if (!stamp.equals(row.targetStamp())) {
                    store.begin(row.id(), stamp, actor);
                    yield Step.MORE;
                }
                if (row.closingAfter() != null && clock.instant().isBefore(row.closingAfter())) {
                    yield Step.WAITING;
                }
                yield sweep(object, row, true, actor);
            }
        };
    }

    /**
     * One batch of records: the next ones in identifier order, or in the closing look the next ones changed since the
     * first sweep began (with a margin as long as the longest write: a write that began before the change has the time
     * of its start).
     */
    private Step sweep(ObjectDefinition object, FormulaRecalculationStore.Row row, boolean closing, UUID actor) {
        QueryFilter changedSince = closing && row.sweepStartedAt() != null
                ? QueryFilter.on("updatedAt", QueryFilter.Op.GTE,
                        row.sweepStartedAt().minus(settings.closingDelay()).toString())
                : null;
        List<DataRecord> page = queries.scan(object, changedSince, row.cursor(), settings.batchSize());
        long changed = 0;
        long unavailable = 0;
        for (DataRecord record : page) {
            FormulaResults.Outcome outcome = results.refresh(object, record);
            changed += outcome.written() ? 1 : 0;
            unavailable += outcome.unavailable() ? 1 : 0;
        }
        UUID last = page.isEmpty() ? row.cursor() : page.get(page.size() - 1).id();
        store.progress(row.id(), last, page.size(), changed, unavailable, actor);
        if (page.size() < settings.batchSize()) {
            return closing ? finish(object, row, actor) : startClosing(row, actor);
        }
        return Step.MORE;
    }

    private Step startClosing(FormulaRecalculationStore.Row row, UUID actor) {
        store.closing(row.id(), settings.closingDelay().toMillis() / 1000.0, actor);
        return Step.MORE;
    }

    private Step finish(ObjectDefinition object, FormulaRecalculationStore.Row row, UUID actor) {
        FormulaRecalculationStore.Row done = store.find(object.apiName()).orElse(row);
        if (store.complete(row.id(), actor)) {
            audit.record(AuditRecord.of("data.formulas.recalculated", AuditOutcome.SUCCESS)
                    .inTenant(contexts.require().tenantId()).onObject(object.apiName(), null)
                    .with("examined", Long.toString(done.examined()))
                    .with("changed", Long.toString(done.changed()))
                    .with("unavailable", Long.toString(done.unavailable()))
                    .with("started_by", done.startedBy() == null ? "RESUME" : done.startedBy().name()));
        }
        return Step.DONE;
    }

    // ---- life cycle ----

    @Override
    public void start() {
        if (!settings.enabled() || executor != null) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(
                runnable -> new Thread(runnable, "formula-recalculation"));
        executor.scheduleWithFixedDelay(() -> {
            try {
                runOnce();
            } catch (RuntimeException e) {
                LOG.warn("The formula recalculation sweep failed ({})", e.getClass().getSimpleName());
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
