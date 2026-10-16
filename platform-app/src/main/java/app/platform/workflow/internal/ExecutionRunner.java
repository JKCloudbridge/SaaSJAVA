package app.platform.workflow.internal;

import app.platform.data.BackgroundRecords;
import app.platform.data.DataRecord;
import app.platform.licensing.Entitlements;
import app.platform.metadata.ConditionOutcome;
import app.platform.metadata.Metadata;
import app.platform.metadata.MetadataResolver;
import app.platform.metadata.PinnedWorkflow;
import app.platform.metadata.WorkflowDefinition;
import app.platform.observability.ErrorReport;
import app.platform.observability.ErrorTracker;
import app.platform.sharedkernel.TenantId;
import app.platform.sharedkernel.logging.LogContext;
import app.platform.tenant.TenantContext;
import app.platform.tenant.TenantContexts;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs one claimed execution (ADR-0137): restores the organization's context, reads the version of the workflow the
 * execution was created under and the record as it is now, judges the condition, and carries the actions out in order.
 * Every outcome is recorded; nothing is lost silently.
 *
 * <p><strong>Transactions.</strong> The decision (is the workflow still on, is the record there, is the condition true)
 * is a read. Each step is a transaction of its own that renews the lease, carries the action out and marks the step
 * completed, so an action's database effects and its step commit together or not at all. An execution taken over
 * after a crash goes on after the steps that completed and never repeats one. The final status is a write of its own
 * that carries the claim's token: an instance that lost its lease cannot overwrite the outcome of the one that took
 * over.
 *
 * <p><strong>No retries here.</strong> A step that throws ends the execution as failed, with the type name of the
 * exception and never its message; retry with backoff, dead letters and replay are Sprint 23. A crash is different: the
 * lease expires, the execution is claimed again and the claim is counted, up to {@code max-attempts}.
 *
 * <p>The condition is judged against the record <em>as it is when this runs</em>, not as it was when the event was
 * made (ADR-0137). Nothing of the record, the condition or the settings is logged.
 */
@Component
class ExecutionRunner {

    private static final Logger LOG = LoggerFactory.getLogger(ExecutionRunner.class);

    /** The plan feature that gates workflows (the licensing catalogue of Sprint 6). */
    private static final String FEATURE = "workflows";

    /** The claim was lost to another instance; the outcome is theirs. */
    private static final class ClaimLost extends RuntimeException {

        private static final long serialVersionUID = 1L;

        ClaimLost() {
            super("The claim of the execution was lost", null, false, false);
        }
    }

    /** What the decision found: either an end with its reason, or the workflow to carry on with. */
    private record Verdict(String status, String reason, PinnedWorkflow pinned) {

        static Verdict end(String status, String reason) {
            return new Verdict(status, reason, null);
        }

        static Verdict go(PinnedWorkflow pinned) {
            return new Verdict(null, null, pinned);
        }
    }

    private final TenantContexts contexts;
    private final TransactionTemplate transaction;
    private final ExecutionStore executions;
    private final MetadataResolver resolver;
    private final Metadata metadata;
    private final BackgroundRecords records;
    private final ActionRegistry actions;
    private final Entitlements entitlements;
    private final WorkflowProperties.Worker settings;
    private final Clock clock;
    private final ErrorTracker tracker;
    private final MeterRegistry meters;
    private final Timer queueDelay;
    private final Timer duration;
    private final Spans spans;

    ExecutionRunner(TenantContexts contexts, PlatformTransactionManager transactions, ExecutionStore executions,
            MetadataResolver resolver, Metadata metadata, BackgroundRecords records, ActionRegistry actions,
            Entitlements entitlements, WorkflowProperties properties, Clock clock, ErrorTracker tracker,
            MeterRegistry meters, Spans spans) {
        this.spans = spans;
        this.contexts = contexts;
        this.transaction = new TransactionTemplate(transactions);
        this.executions = executions;
        this.resolver = resolver;
        this.metadata = metadata;
        this.records = records;
        this.actions = actions;
        this.entitlements = entitlements;
        this.settings = properties.worker();
        this.clock = clock;
        this.tracker = tracker;
        this.meters = meters;
        this.queueDelay = Timer.builder("platform.workflow.queue.delay").publishPercentileHistogram()
                .register(meters);
        this.duration = Timer.builder("platform.workflow.execution.duration").publishPercentileHistogram()
                .register(meters);
    }

    /** Runs the claimed execution in its organization's context. Never throws. */
    void run(DispatchStore.ClaimedExecution claimed) {
        TenantContext context = TenantContext.of(new TenantId(claimed.tenantId()));
        Instant started = clock.instant();
        queueDelay.record(Duration.between(claimed.queuedAt(), started));
        try {
            contexts.run(context, () -> spans.run("workflow.execution", Map.of("workflow.execution.id",
                    claimed.id().toString(), "workflow.trigger", claimed.trigger(), "workflow.attempt",
                    Integer.toString(claimed.attempt())), () -> execute(claimed)));
        } catch (ClaimLost lost) {
            meters.counter("platform.workflow.executions", "result", "claim_lost").increment();
            LOG.info("An execution was taken over by another instance; its outcome is theirs");
        } catch (RuntimeException unexpected) {
            // The lease expires and the execution is claimed again, counted; the reason class is enough here.
            LOG.warn("Running an execution failed unexpectedly: {}", unexpected.getClass().getName());
        } finally {
            duration.record(Duration.between(started, clock.instant()));
        }
    }

    private void execute(DispatchStore.ClaimedExecution claimed) {
        long token = claimed.version();
        if (claimed.attempt() > settings.maxAttempts()) {
            // Earlier claims never reported back: the process died or stalled each time (ADR-0137).
            end(claimed, token, "FAILED", "attempts_exhausted", "AttemptsExhausted");
            return;
        }
        Verdict verdict;
        try {
            verdict = transaction.execute(status -> decide(claimed));
        } catch (RuntimeException failure) {
            String type = failure.getClass().getSimpleName();
            end(claimed, token, "FAILED", "decision_failed", type);
            tracker.track(new ErrorReport(failure, ErrorReport.UNKNOWN_REQUEST, LogContext.traceId(),
                    LogContext.tenantId(), "WORKFLOW", claimed.trigger()));
            return;
        }
        if (verdict == null) {
            return;
        }
        if (verdict.pinned() == null) {
            end(claimed, token, verdict.status(), verdict.reason(), null);
            return;
        }
        List<WorkflowDefinition.Action> steps = verdict.pinned().workflow().definition().actions();
        Set<Integer> done = new HashSet<>(transaction.execute(status -> executions.completedPositions(claimed.id())));
        for (int index = 0; index < steps.size(); index++) {
            int position = index + 1;
            if (done.contains(position)) {
                continue;
            }
            WorkflowDefinition.Action action = steps.get(index);
            Optional<Long> next = step(claimed, token, position, action, verdict.pinned());
            if (next.isEmpty()) {
                return;
            }
            token = next.get();
        }
        end(claimed, token, "COMPLETED", null, null);
    }

    // ---- the decision ----

    private Verdict decide(DispatchStore.ClaimedExecution claimed) {
        Optional<PinnedWorkflow> found = resolver.workflowVersion(claimed.versionId());
        if (found.isEmpty()) {
            return Verdict.end("CANCELLED", "version_missing");
        }
        PinnedWorkflow pinned = found.get();
        if (pinned.removed()) {
            return Verdict.end("CANCELLED", "workflow_removed");
        }
        if (!pinned.active()) {
            return Verdict.end("SKIPPED", "workflow_inactive");
        }
        if (!entitlements.enabled(FEATURE)) {
            return Verdict.end("SKIPPED", "feature_off");
        }
        if (metadata.object(claimed.objectApiName()).isEmpty()) {
            return Verdict.end("SKIPPED", "object_missing");
        }
        DataRecord record = records.find(claimed.objectApiName(), claimed.recordId()).orElse(null);
        if (record == null) {
            return Verdict.end("SKIPPED", "record_gone");
        }
        boolean wantsDeleted = WorkflowDefinition.Kind.RECORD_DELETED.name().equals(claimed.trigger());
        if (record.deleted() != wantsDeleted) {
            return Verdict.end("SKIPPED", record.deleted() ? "record_gone" : "record_restored");
        }
        ConditionOutcome outcome = pinned.workflow().evaluate(
                records.engineValues(claimed.objectApiName(), record), clock.instant());
        return switch (outcome) {
            case TRUE -> Verdict.go(pinned);
            case FALSE -> Verdict.end("SKIPPED", "condition_false");
            case NOT_EVALUABLE -> Verdict.end("SKIPPED", "condition_not_evaluable");
        };
    }

    // ---- one step ----

    /**
     * Carries one action out.
     *
     * @return the claim's token after the step, or empty when the execution ended (the step failed)
     */
    private Optional<Long> step(DispatchStore.ClaimedExecution claimed, long token, int position,
            WorkflowDefinition.Action action, PinnedWorkflow pinned) {
        try {
            Long next = transaction.execute(status -> {
                Optional<Long> renewed = executions.renew(claimed.id(), token, settings.lease());
                if (renewed.isEmpty()) {
                    throw new ClaimLost();
                }
                ActionExecutor executor = actions.find(action.type()).orElseThrow(
                        () -> new UnknownActionType());
                executions.startStep(claimed.id(), position, action.name(), action.type());
                executor.execute(new ActionExecutor.ActionContext(claimed.id(), pinned.workflow().definition()
                        .apiName(), claimed.objectApiName(), claimed.recordId(), claimed.recordVersion(), position,
                        action.name(), action.settings()));
                executions.completeStep(claimed.id(), position);
                return renewed.get();
            });
            return Optional.of(next);
        } catch (ClaimLost lost) {
            throw lost;
        } catch (RuntimeException failure) {
            String type = failure.getClass().getSimpleName();
            Boolean ended = transaction.execute(status -> {
                executions.failedStep(claimed.id(), position, action.name(), action.type(), type);
                return executions.finish(claimed.id(), token, "FAILED", "action_failed", type);
            });
            if (!Boolean.TRUE.equals(ended)) {
                throw new ClaimLost();
            }
            meters.counter("platform.workflow.executions", "result", "failed").increment();
            LOG.warn("A workflow step failed: {}", type);
            tracker.track(new ErrorReport(failure, ErrorReport.UNKNOWN_REQUEST, LogContext.traceId(),
                    LogContext.tenantId(), "WORKFLOW", action.type()));
            return Optional.empty();
        }
    }

    /** A stored action whose type this release does not carry out (a definition of a later release). */
    private static final class UnknownActionType extends RuntimeException {

        private static final long serialVersionUID = 1L;

        UnknownActionType() {
            super("The action type is not carried out by this release", null, false, false);
        }
    }

    // ---- the end ----

    private void end(DispatchStore.ClaimedExecution claimed, long token, String status, String reason,
            String failureType) {
        Boolean closed = transaction.execute(s -> executions.finish(claimed.id(), token, status, reason, failureType));
        if (!Boolean.TRUE.equals(closed)) {
            throw new ClaimLost();
        }
        meters.counter("platform.workflow.executions", "result", status.toLowerCase(Locale.ROOT)).increment();
        LOG.debug("Execution {} ended {} {}", claimed.id(), status, reason);
    }
}
