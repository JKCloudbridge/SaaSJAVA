package app.platform.workflow.internal;

import app.platform.data.BackgroundRecords;
import app.platform.data.DataRecord;
import app.platform.metadata.ConditionOutcome;
import app.platform.metadata.FieldDefinition;
import app.platform.metadata.FieldType;
import app.platform.metadata.Metadata;
import app.platform.metadata.MetadataResolver;
import app.platform.metadata.PinnedWorkflow;
import app.platform.metadata.WorkflowDefinition;
import app.platform.observability.ErrorReport;
import app.platform.observability.ErrorTracker;
import app.platform.sharedkernel.TenantId;
import app.platform.sharedkernel.audit.AuditOutcome;
import app.platform.sharedkernel.audit.AuditRecord;
import app.platform.sharedkernel.audit.AuditRecorder;
import app.platform.sharedkernel.logging.LogContext;
import app.platform.tenant.TenantContext;
import app.platform.tenant.TenantContexts;
import app.platformapi.QueryFilterNode;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Fires one claimed schedule (ADR-0135): selects the records of the workflow with the query engine's own stages, in
 * bounded pages, and makes one execution per record and firing. Everything runs inside the organization's own tenant
 * context; the system scope was used only to claim the row.
 *
 * <p><strong>Once per firing.</strong> The key of an execution is the version of the definition, the instant of the
 * firing and the record, so a firing that is retried, resumed by another instance after a crash, or claimed twice makes
 * no second execution. A firing records how far through the records it got after every page, so a dead instance's
 * firing is taken over where it stopped.
 *
 * <p><strong>A missed time is not made up in a burst.</strong> When the platform was down at the time of a firing, the
 * firing that runs is the latest due occurrence ({@link ScheduleMath#latestDue}); the days-after-date trigger loses no
 * record by that, because its window starts where the covered period ended.
 *
 * <p>A record is not given an execution when the condition is false for it (a daily workflow over a large object would
 * otherwise write a skipped row per record per day); a condition that cannot be evaluated does make one, so the reason
 * is recorded. The condition is judged again when the execution runs, against the record as it is then.
 */
@Component
class ScheduleRunner {

    private static final Logger LOG = LoggerFactory.getLogger(ScheduleRunner.class);
    private static final Duration LONGEST_PAUSE = Duration.ofMinutes(10);

    /** The claim was lost to another instance; the outcome is theirs. */
    private static final class ClaimLost extends RuntimeException {

        private static final long serialVersionUID = 1L;

        ClaimLost() {
            super("The claim of the schedule was lost", null, false, false);
        }
    }

    /** What one firing is working on, and the token its claim has now (it moves with every write). */
    private static final class Firing {

        private final DispatchStore.ClaimedSchedule claimed;
        private final WorkflowDefinition definition;
        private final PinnedWorkflow pinned;
        private final Instant at;
        private long token;
        private int made;
        private boolean limited;

        Firing(DispatchStore.ClaimedSchedule claimed, WorkflowDefinition definition, PinnedWorkflow pinned,
                Instant at) {
            this.claimed = claimed;
            this.definition = definition;
            this.pinned = pinned;
            this.at = at;
            this.token = claimed.version();
        }
    }

    private record Page(int size, UUID last, int made) {
    }

    private final TenantContexts contexts;
    private final TransactionTemplate transaction;
    private final ScheduleStore schedules;
    private final ExecutionStore executions;
    private final MetadataResolver resolver;
    private final Metadata metadata;
    private final BackgroundRecords records;
    private final ScheduleReconciler reconciler;
    private final WorkflowProperties.Scheduler settings;
    private final Clock clock;
    private final ErrorTracker tracker;
    private final AuditRecorder audit;
    private final MeterRegistry meters;
    private final Spans spans;

    ScheduleRunner(TenantContexts contexts, PlatformTransactionManager transactions, ScheduleStore schedules,
            ExecutionStore executions, MetadataResolver resolver, Metadata metadata, BackgroundRecords records,
            ScheduleReconciler reconciler, WorkflowProperties properties, Clock clock, ErrorTracker tracker,
            AuditRecorder audit, MeterRegistry meters, Spans spans) {
        this.spans = spans;
        this.contexts = contexts;
        this.transaction = new TransactionTemplate(transactions);
        this.schedules = schedules;
        this.executions = executions;
        this.resolver = resolver;
        this.metadata = metadata;
        this.records = records;
        this.reconciler = reconciler;
        this.settings = properties.scheduler();
        this.clock = clock;
        this.tracker = tracker;
        this.audit = audit;
        this.meters = meters;
    }

    /** Fires the claimed schedule in its organization's context. Never throws. */
    void run(DispatchStore.ClaimedSchedule claimed) {
        TenantContext context = TenantContext.of(new TenantId(claimed.tenantId()));
        try {
            contexts.run(context, () -> spans.run("workflow.schedule", Map.of("workflow.schedule.id",
                    claimed.id().toString(), "workflow.trigger", claimed.trigger()), () -> fire(claimed)));
        } catch (ClaimLost lost) {
            meters.counter("platform.workflow.schedule.firings", "result", "claim_lost").increment();
            LOG.info("A schedule was taken over by another instance; its outcome is theirs");
        } catch (RuntimeException unexpected) {
            LOG.warn("Firing a schedule failed: {}", unexpected.getClass().getName());
        }
    }

    private void fire(DispatchStore.ClaimedSchedule claimed) {
        Firing firing = transaction.execute(status -> begin(claimed));
        if (firing == null) {
            return;
        }
        try {
            fireRecords(firing);
            close(firing, firing.limited ? "FiringLimitReached" : null);
        } catch (ClaimLost lost) {
            throw lost;
        } catch (RuntimeException failure) {
            failed(firing, failure);
        }
    }

    /**
     * Reads the definition the schedule was planned for. Returns null, after putting the rows right, when the
     * definition no longer applies (switched off, removed, changed, or no longer scheduled).
     */
    private Firing begin(DispatchStore.ClaimedSchedule claimed) {
        WorkflowDefinition definition = metadata.workflows().stream()
                .filter(workflow -> workflow.id().equals(claimed.workflowId())).findFirst().orElse(null);
        PinnedWorkflow pinned = definition == null ? null : resolver.workflowVersion(definition.versionId())
                .orElse(null);
        if (definition == null || pinned == null || !definition.active() || !definition.trigger().kind().scheduled()
                || definition.definitionVersion() != claimed.definitionVersion()
                || !definition.trigger().kind().name().equals(claimed.trigger())) {
            reconciler.reconcile();
            return null;
        }
        Instant at = claimed.firingFor() != null ? claimed.firingFor()
                : ScheduleMath.latestDue(definition.trigger(), claimed.nextFireAt(), clock.instant());
        return new Firing(claimed, definition, pinned, at);
    }

    private void fireRecords(Firing firing) {
        boolean window = firing.definition.trigger().kind() == WorkflowDefinition.Kind.DAYS_AFTER_DATE;
        QueryFilterNode filter = filter(firing);
        firing.token = renew(firing, firing.claimed.firingAfter());
        if (window && filter == null) {
            return;
        }
        UUID after = firing.claimed.firingAfter();
        while (true) {
            UUID cursor = after;
            Page page = transaction.execute(status -> page(firing, filter, cursor));
            firing.made += page.made();
            if (page.size() < settings.pageSize()) {
                return;
            }
            after = page.last();
            firing.token = renew(firing, after);
            if (firing.made >= settings.maxRecordsPerFiring()) {
                firing.limited = true;
                return;
            }
        }
    }

    private long renew(Firing firing, UUID after) {
        Optional<Long> renewed = transaction.execute(status -> schedules.progress(firing.claimed.id(), firing.token,
                firing.at, after, settings.lease()));
        if (renewed == null || renewed.isEmpty()) {
            throw new ClaimLost();
        }
        return renewed.get();
    }

    /** One page: selects the records, judges the condition, makes the executions. */
    private Page page(Firing firing, QueryFilterNode filter, UUID after) {
        WorkflowDefinition definition = firing.definition;
        List<DataRecord> page = records.scan(definition.objectApiName(), filter, after, settings.pageSize());
        Instant now = clock.instant();
        int made = 0;
        for (DataRecord record : page) {
            ConditionOutcome outcome = firing.pinned.workflow().evaluate(
                    records.engineValues(definition.objectApiName(), record), now);
            if (outcome == ConditionOutcome.FALSE) {
                continue;
            }
            boolean inserted = executions.insert(new ExecutionStore.NewExecution(definition.id(),
                    definition.versionId(), definition.apiName(), definition.definitionVersion(),
                    definition.objectApiName(), record.id(), null, definition.trigger().kind().name(), null,
                    firing.at, "firing:" + definition.versionId() + ":" + firing.at.toEpochMilli() + ":"
                            + record.id()));
            if (inserted) {
                made++;
                meters.counter("platform.workflow.executions", "result", "queued").increment();
            }
        }
        return new Page(page.size(), page.isEmpty() ? after : page.get(page.size() - 1).id(), made);
    }

    // ---- the selection of records ----

    /**
     * The records one firing considers: the selection of the definition and, for the days-after trigger, the window of
     * dates that have come due since the period the schedule has already covered. Null for a window with no date in it
     * (the caller knows which kind it asked).
     */
    private QueryFilterNode filter(Firing firing) {
        WorkflowDefinition.Trigger trigger = firing.definition.trigger();
        QueryFilterNode selection = trigger.selection();
        if (trigger.kind() != WorkflowDefinition.Kind.DAYS_AFTER_DATE) {
            return selection;
        }
        ZoneId zone = trigger.timeZone();
        LocalDate to = firing.at.atZone(zone).toLocalDate().minusDays(trigger.days());
        Instant covered = firing.claimed.coveredThrough();
        LocalDate from = covered == null ? to
                : covered.atZone(zone).toLocalDate().minusDays(trigger.days()).plusDays(1);
        if (from.isAfter(to)) {
            return null;
        }
        FieldDefinition field = metadata.object(firing.definition.objectApiName())
                .flatMap(object -> object.field(trigger.field()))
                .orElseThrow(() -> new IllegalStateException("The date field of the workflow no longer exists"));
        List<QueryFilterNode> parts = new ArrayList<>();
        if (selection != null) {
            parts.add(selection);
        }
        if (field.type() == FieldType.DATE) {
            parts.add(condition("GTE", field.apiName(), from.toString()));
            parts.add(condition("LTE", field.apiName(), to.toString()));
        } else {
            parts.add(condition("GTE", field.apiName(), ScheduleMath.startOfDay(from, zone).toString()));
            parts.add(condition("LT", field.apiName(), ScheduleMath.startOfDay(to.plusDays(1), zone).toString()));
        }
        return new QueryFilterNode("AND", parts, null, null);
    }

    private static QueryFilterNode condition(String op, String field, String value) {
        return new QueryFilterNode(op, null, field, List.of(value));
    }

    // ---- the end of a firing ----

    /** Plans the next firing; a one-time schedule is done. */
    private void close(Firing firing, String stopped) {
        WorkflowDefinition.Trigger trigger = firing.definition.trigger();
        boolean once = trigger.kind() == WorkflowDefinition.Kind.DATE_TIME;
        Instant next = once ? firing.at : ScheduleMath.next(trigger, firing.at).orElse(firing.at);
        boolean window = trigger.kind() == WorkflowDefinition.Kind.DAYS_AFTER_DATE;
        Boolean closed = transaction.execute(status -> schedules.complete(firing.claimed.id(), firing.token, next,
                once ? "DONE" : "WAITING", window ? firing.at : null, firing.at, stopped));
        if (!Boolean.TRUE.equals(closed)) {
            throw new ClaimLost();
        }
        meters.counter("platform.workflow.schedule.firings", "result", stopped == null ? "fired" : "limit")
                .increment();
        record(firing, stopped);
    }

    private void failed(Firing firing, RuntimeException failure) {
        String type = failure.getClass().getSimpleName();
        int attempts = firing.claimed.attempts() + 1;
        if (attempts >= settings.maxAttempts()) {
            // Give this firing up so one broken definition cannot hold the schedule forever; the next one starts
            // afresh.
            close(firing, type);
            meters.counter("platform.workflow.schedule.firings", "result", "given_up").increment();
            tracker.track(new ErrorReport(failure, ErrorReport.UNKNOWN_REQUEST, LogContext.traceId(),
                    LogContext.tenantId(), "WORKFLOW", firing.definition.apiName()));
            return;
        }
        Duration pause = Duration.ofMinutes(Math.min(attempts, LONGEST_PAUSE.toMinutes()));
        Boolean kept = transaction.execute(status -> schedules.retryLater(firing.claimed.id(), firing.token, attempts,
                type, pause));
        if (!Boolean.TRUE.equals(kept)) {
            throw new ClaimLost();
        }
        meters.counter("platform.workflow.schedule.firings", "result", "retry").increment();
        LOG.warn("Firing a schedule failed, will try again: {}", type);
    }

    /** One audit record per firing: the workflow, the kind of trigger and how many executions it made. */
    private void record(Firing firing, String stopped) {
        TenantId tenant = contexts.current().map(TenantContext::tenantId).orElse(null);
        AuditRecord record = AuditRecord.of("workflow.schedule.fired", AuditOutcome.SUCCESS).inTenant(tenant)
                .onObject(firing.definition.objectApiName() + "." + firing.definition.apiName(), null)
                .with("trigger", firing.definition.trigger().kind().name())
                .with("executions", Integer.toString(firing.made));
        AuditRecord written = stopped == null ? record : record.with("stopped", stopped);
        transaction.executeWithoutResult(status -> audit.record(written));
    }
}
