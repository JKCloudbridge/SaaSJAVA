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
import io.micrometer.core.instrument.DistributionSummary;
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
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs one claimed execution (ADR-0137, ADR-0141, ADR-0142): restores the organization's context, reads the version of
 * the workflow the execution was created under and the record as it is now, judges the condition, and carries the
 * actions out in order. Every outcome is recorded; nothing is lost silently.
 *
 * <p><strong>Transactions.</strong> The decision (is the workflow still on, is the record there, is the condition true)
 * is a read. Each attempt of a step is a transaction of its own that renews the lease, carries the action out and marks
 * the step completed, so an action's database effects and its step commit together or not at all. An execution taken
 * over after a crash goes on after the steps that completed and never repeats one. The final status is a write of its
 * own that carries the claim's token: an instance that lost its lease cannot overwrite the outcome of the one that
 * took over.
 *
 * <p><strong>Retries.</strong> An attempt that fails is classified from the type of the failure (never its message,
 * {@link RetryPolicy}). A transient failure with attempts left rolls the attempt back, records it on the step and puts
 * the execution back in the queue, due after a backoff: no thread sleeps and no connection or lock is held while it
 * waits. A permanent failure, or the last failed attempt, ends the execution as a dead letter that a person can replay.
 * Attempts of a step and claims of an execution are different counters: a crash is not a failed attempt, and a failed
 * attempt is not a crash.
 *
 * <p>The condition is judged against the record <em>as it is when this runs</em>, not as it was when the event was
 * made, except in a replay, where the person has decided to run it (ADR-0141). Nothing of the record, the condition or
 * the settings is logged.
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

    /** What became of one step. */
    private sealed interface StepOutcome {

        /** The step completed; the claim's token is now this. */
        record Done(long token) implements StepOutcome {
        }

        /** The step failed and will be attempted again later, or the execution ended; nothing more to do now. */
        record Stopped() implements StepOutcome {
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
    private final RetryPolicy retry;
    private final Clock clock;
    private final ErrorTracker tracker;
    private final MeterRegistry meters;
    private final Timer queueDelay;
    private final Timer duration;
    private final DistributionSummary retryDelay;
    private final Spans spans;

    ExecutionRunner(TenantContexts contexts, PlatformTransactionManager transactions, ExecutionStore executions,
            MetadataResolver resolver, Metadata metadata, BackgroundRecords records, ActionRegistry actions,
            Entitlements entitlements, WorkflowProperties properties, RetryPolicy retry, Clock clock,
            ErrorTracker tracker, MeterRegistry meters, Spans spans) {
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
        this.retry = retry;
        this.clock = clock;
        this.tracker = tracker;
        this.meters = meters;
        this.queueDelay = Timer.builder("platform.workflow.queue.delay").publishPercentileHistogram()
                .register(meters);
        this.duration = Timer.builder("platform.workflow.execution.duration").publishPercentileHistogram()
                .register(meters);
        this.retryDelay = DistributionSummary.builder("platform.workflow.retry.delay").baseUnit("seconds")
                .publishPercentileHistogram().register(meters);
    }

    /** Runs the claimed execution in its organization's context. Never throws. */
    void run(DispatchStore.ClaimedExecution claimed) {
        TenantContext context = TenantContext.of(new TenantId(claimed.tenantId()));
        Instant started = clock.instant();
        queueDelay.record(Duration.between(claimed.queuedAt(), started));
        try {
            contexts.run(context, () -> spans.run("workflow.execution", Map.of("workflow.execution.id",
                    claimed.id().toString(), "workflow.trigger", claimed.trigger(), "workflow.claim",
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
            end(claimed, token, "DEAD_LETTERED", "attempts_exhausted", "AttemptsExhausted");
            return;
        }
        Verdict verdict;
        try {
            verdict = transaction.execute(status -> decide(claimed));
        } catch (RuntimeException failure) {
            RetryPolicy.Failure how = RetryPolicy.classify(failure);
            if (how.transientFailure() && claimed.attempt() < settings.maxAttempts()) {
                Duration delay = retry.delay(claimed.attempt(), ThreadLocalRandom.current().nextDouble());
                waitFor(claimed, token, delay, how.type());
                return;
            }
            end(claimed, token, "DEAD_LETTERED", "decision_failed", how.type());
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
        Map<Integer, ExecutionStore.StepState> states = new HashMap<>();
        transaction.execute(status -> executions.stepStates(claimed.id())).forEach(
                state -> states.put(state.position(), state));
        Chain chain = new Chain(claimed.chainRoot(), claimed.chainDepth(), claimed.chainWorkflows())
                .extendedBy(claimed.workflowId(), claimed.id());
        for (int index = 0; index < steps.size(); index++) {
            int position = index + 1;
            ExecutionStore.StepState state = states.get(position);
            // A replay resumes at a step: the ones before it completed in the execution it replays.
            if (position < claimed.resumeStep() || state != null && "COMPLETED".equals(state.status())) {
                continue;
            }
            int attempt = (state == null ? 0 : state.attempts()) + 1;
            StepOutcome outcome = step(claimed, token, position, attempt, steps.get(index), verdict.pinned(), chain);
            if (outcome instanceof StepOutcome.Done done) {
                token = done.token();
            } else {
                return;
            }
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
        if (claimed.replayOf() != null) {
            // A person decided to run it again; the condition is not judged a second time (ADR-0141).
            return Verdict.go(pinned);
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
     * Makes one attempt of one step.
     *
     * @param attempt the number of this attempt, counting the ones that ended before it
     */
    private StepOutcome step(DispatchStore.ClaimedExecution claimed, long token, int position, int attempt,
            WorkflowDefinition.Action action, PinnedWorkflow pinned, Chain chain) {
        try {
            Long next = spans.call("workflow.attempt", Map.of("workflow.execution.id", claimed.id().toString(),
                    "workflow.step", Integer.toString(position), "workflow.attempt", Integer.toString(attempt),
                    "workflow.action", action.type()), () -> transaction.execute(status -> {
                        Optional<Long> renewed = executions.renew(claimed.id(), token, settings.lease());
                        if (renewed.isEmpty()) {
                            throw new ClaimLost();
                        }
                        ActionExecutor executor = actions.find(action.type()).orElseThrow(
                                () -> StepFailure.permanent("unknown_action_type"));
                        executions.startStep(claimed.id(), position, action.name(), action.type());
                        executor.execute(new ActionExecutor.ActionContext(claimed.id(), claimed.workflowId(),
                                pinned.workflow().definition().apiName(), claimed.objectApiName(), claimed.recordId(),
                                claimed.recordVersion(), position, action.name(), action.settings(),
                                action.assignments(), chain, claimed.replayGeneration()));
                        executions.completeStep(claimed.id(), position);
                        return renewed.get();
                    }));
            meters.counter("platform.workflow.attempts", "action", action.type(), "result", "succeeded").increment();
            return new StepOutcome.Done(next);
        } catch (ClaimLost lost) {
            throw lost;
        } catch (RuntimeException failure) {
            return failed(claimed, token, position, attempt, action, failure);
        }
    }

    /** The attempt failed and was rolled back: retry it later or give the execution to a person. */
    private StepOutcome failed(DispatchStore.ClaimedExecution claimed, long token, int position, int attempt,
            WorkflowDefinition.Action action, RuntimeException failure) {
        RetryPolicy.Failure how = RetryPolicy.classify(failure);
        boolean again = how.transientFailure() && attempt < retry.attemptsAllowed(action.maxAttempts());
        Duration delay = again ? retry.delay(attempt, ThreadLocalRandom.current().nextDouble()) : null;
        String reason = how.transientFailure() && !again ? "retries_exhausted" : how.reason();
        Boolean settled = transaction.execute(status -> {
            executions.recordFailedAttempt(claimed.id(), position, action.name(), action.type(),
                    again ? ExecutionStore.Attempt.RETRYING : ExecutionStore.Attempt.FAILED, delay, how.type(),
                    how.reason());
            return again
                    ? executions.reschedule(claimed.id(), token, delay, "retry_scheduled", how.type(), true)
                    : executions.finish(claimed.id(), token, "DEAD_LETTERED", reason, how.type());
        });
        if (!Boolean.TRUE.equals(settled)) {
            throw new ClaimLost();
        }
        meters.counter("platform.workflow.attempts", "action", action.type(), "result",
                again ? "retry" : "dead_lettered").increment();
        if (again) {
            meters.counter("platform.workflow.retries").increment();
            retryDelay.record(delay.toMillis() / 1000.0);
            LOG.info("A workflow step failed and will be attempted again: {} {}", how.type(), how.reason());
        } else {
            meters.counter("platform.workflow.executions", "result", "dead_lettered").increment();
            LOG.warn("A workflow step failed for good: {} {}", how.type(), how.reason());
        }
        // A refusal of the data or a reason the action named is an outcome of the definition and the data, not an
        // error of the platform; what nobody classified, and a failure that outlasted its retries, is reported.
        if (RetryPolicy.ACTION_FAILED.equals(how.reason()) || RetryPolicy.TRANSIENT.equals(how.reason())) {
            tracker.track(new ErrorReport(failure, ErrorReport.UNKNOWN_REQUEST, LogContext.traceId(),
                    LogContext.tenantId(), "WORKFLOW", action.type()));
        }
        return new StepOutcome.Stopped();
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

    /** Puts the execution back for another claim after a failure to decide (the claims are bounded). */
    private void waitFor(DispatchStore.ClaimedExecution claimed, long token, Duration delay, String failureType) {
        Boolean waiting = transaction.execute(s -> executions.reschedule(claimed.id(), token, delay,
                "retry_scheduled", failureType, false));
        if (!Boolean.TRUE.equals(waiting)) {
            throw new ClaimLost();
        }
        meters.counter("platform.workflow.retries").increment();
        retryDelay.record(delay.toMillis() / 1000.0);
    }
}
