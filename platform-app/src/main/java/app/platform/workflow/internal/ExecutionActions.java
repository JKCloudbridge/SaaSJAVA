package app.platform.workflow.internal;

import app.platform.identity.OrganizationAdministration.Caller;
import app.platform.sharedkernel.audit.AuditOutcome;
import app.platform.sharedkernel.audit.AuditRecord;
import app.platform.sharedkernel.audit.AuditRecorder;
import app.platform.sharedkernel.events.ParkedEvents;
import app.platform.tenant.TenantContext;
import app.platform.tenant.TenantContexts;
import app.platformapi.ApiException;
import app.platformapi.ErrorCode;
import app.platformapi.ParkedEventView;
import app.platformapi.WorkflowBulkReplayView;
import app.platformapi.WorkflowExecutionView;
import app.platformapi.WorkflowReplayResultView;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * What a person with the right ability does about executions that need them (ADR-0141, ADR-0143): replay a dead letter,
 * cancel an execution that has not started, put back an event the relay gave up on. Each runs inside the transaction
 * that the caller's ability check opened, in the organization the host names; every one is audited by names,
 * identifiers and counts, never a value.
 *
 * <p><strong>Replay</strong> makes a new execution that resumes at the step that failed (or a later one the person
 * chooses: a step that can never succeed can be passed over, but a completed step is never run again, so a replay
 * cannot duplicate what already happened). Two replays at the same moment make one: the second finds the first.
 */
@Service
class ExecutionActions {

    /** The events the workflow consumers depend on: a record change, and a release that moves schedules. */
    static final Set<String> WORKFLOW_EVENT_TYPES = Set.of("record.created", "record.updated", "record.deleted",
            "metadata.published");

    private static final int MOST_PARKED = 200;

    private final ExecutionStore store;
    private final ParkedEvents parked;
    private final AuditRecorder audit;
    private final TenantContexts contexts;
    private final MeterRegistry meters;
    private final TransactionTemplate savepoint;

    ExecutionActions(ExecutionStore store, ParkedEvents parked, AuditRecorder audit, TenantContexts contexts,
            MeterRegistry meters, PlatformTransactionManager transactions) {
        this.store = store;
        this.parked = parked;
        this.audit = audit;
        this.contexts = contexts;
        this.meters = meters;
        this.savepoint = new TransactionTemplate(transactions);
        this.savepoint.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
    }

    // ---- replay ----

    /**
     * Replays one execution.
     *
     * @param fromStep the step to resume at, or null for the one that failed
     * @throws ApiException {@code NOT_FOUND} for an execution the organization does not have, {@code VALIDATION_ERROR}
     *         for a step before the one that failed
     */
    WorkflowReplayResultView replay(Caller caller, UUID id, Integer fromStep) {
        ExecutionStore.Replayable found = store.replayable(id).orElseThrow(
                () -> ApiException.notFound("This execution does not exist."));
        int resume = resumeStep(found);
        if (fromStep != null && fromStep < resume) {
            throw ApiException.validation("fromStep", "A step that completed is not run again. Choose step " + resume
                    + " or a later one.");
        }
        WorkflowReplayResultView result = replay(found, fromStep == null ? resume : fromStep);
        record(caller, "workflow.execution.replayed", id, result);
        return result;
    }

    /** Replays several; one result each, a savepoint each, nothing all-or-none. */
    WorkflowBulkReplayView replayAll(Caller caller, List<UUID> ids) {
        List<WorkflowReplayResultView> results = new ArrayList<>();
        for (UUID id : ids.stream().distinct().toList()) {
            WorkflowReplayResultView result;
            try {
                result = savepoint.execute(status -> {
                    Optional<ExecutionStore.Replayable> found = store.replayable(id);
                    return found.isEmpty()
                            ? new WorkflowReplayResultView(id, "NOT_FOUND", null, null)
                            : replay(found.get(), resumeStep(found.get()));
                });
            } catch (RuntimeException failure) {
                // Nothing is said about why: the item simply could not be replayed now, and the others go on.
                result = new WorkflowReplayResultView(id, "NOT_REPLAYABLE", null, "replay_failed");
            }
            results.add(result);
        }
        long made = results.stream().filter(result -> "REPLAYED".equals(result.outcome())).count();
        audit.record(AuditRecord.of("workflow.execution.replayed_many", AuditOutcome.SUCCESS).forUser(caller.userId())
                .inTenant(tenant()).with("requested", Integer.toString(results.size()))
                .with("replayed", Long.toString(made))
                .with("already_replayed", Long.toString(count(results, "ALREADY_REPLAYED")))
                .with("refused", Long.toString(results.size() - made - count(results, "ALREADY_REPLAYED"))));
        return new WorkflowBulkReplayView(results);
    }

    private WorkflowReplayResultView replay(ExecutionStore.Replayable found, int resume) {
        UUID id = found.id();
        if (!"DEAD_LETTERED".equals(found.status()) && !"FAILED".equals(found.status())) {
            meters.counter("platform.workflow.replays", "result", "not_replayable").increment();
            return new WorkflowReplayResultView(id, "NOT_REPLAYABLE", null, "not_dead_lettered");
        }
        Optional<UUID> existing = store.replayOf(id);
        if (existing.isPresent()) {
            meters.counter("platform.workflow.replays", "result", "already_replayed").increment();
            return new WorkflowReplayResultView(id, "ALREADY_REPLAYED", existing.get(), null);
        }
        Optional<UUID> made = store.insertReplay(id, resume);
        if (made.isEmpty()) {
            // Another click got there between the look and the insert: the unique key made one execution.
            meters.counter("platform.workflow.replays", "result", "already_replayed").increment();
            return new WorkflowReplayResultView(id, "ALREADY_REPLAYED", store.replayOf(id).orElse(null), null);
        }
        meters.counter("platform.workflow.replays", "result", "replayed").increment();
        return new WorkflowReplayResultView(id, "REPLAYED", made.get(), null);
    }

    /** The step a replay resumes at: the one that failed, else the one after those that completed. */
    private static int resumeStep(ExecutionStore.Replayable found) {
        return found.failedPosition() != null ? found.failedPosition() : found.stepsDone() + 1;
    }

    private static long count(List<WorkflowReplayResultView> results, String outcome) {
        return results.stream().filter(result -> outcome.equals(result.outcome())).count();
    }

    // ---- cancel ----

    /**
     * Cancels an execution that has not started or waits for a retry.
     *
     * @throws ApiException {@code NOT_FOUND}, or {@code CONFLICT} when the execution is already running or finished
     */
    WorkflowExecutionView cancel(Caller caller, UUID id) {
        store.get(id).orElseThrow(() -> ApiException.notFound("This execution does not exist."));
        if (!store.cancel(id)) {
            throw new ApiException(ErrorCode.CONFLICT,
                    "Only an execution that has not started, or waits for its next attempt, can be cancelled.");
        }
        audit.record(AuditRecord.of("workflow.execution.cancelled", AuditOutcome.SUCCESS).forUser(caller.userId())
                .inTenant(tenant()).with("execution", id.toString()));
        meters.counter("platform.workflow.executions", "result", "cancelled_by_person").increment();
        return store.get(id).orElseThrow();
    }

    // ---- parked events ----

    List<ParkedEventView> parkedEvents() {
        return parked.list(WORKFLOW_EVENT_TYPES, MOST_PARKED).stream().map(event -> new ParkedEventView(event.id(),
                event.type(), event.occurredAt(), event.attempts(), event.lastFailureType())).toList();
    }

    /**
     * Puts a parked event back.
     *
     * @throws ApiException {@code NOT_FOUND} when there is no such parked event (it may be redriven already)
     */
    void redrive(Caller caller, UUID eventId) {
        if (!parked.redrive(eventId, WORKFLOW_EVENT_TYPES)) {
            throw ApiException.notFound("There is no parked event with this identifier.");
        }
        audit.record(AuditRecord.of("workflow.event.redriven", AuditOutcome.SUCCESS).forUser(caller.userId())
                .inTenant(tenant()).with("event", eventId.toString()));
        meters.counter("platform.workflow.redrives").increment();
    }

    // ---- helpers ----

    private void record(Caller caller, String type, UUID id, WorkflowReplayResultView result) {
        AuditRecord record = AuditRecord.of(type, AuditOutcome.SUCCESS).forUser(caller.userId()).inTenant(tenant())
                .with("execution", id.toString()).with("outcome", result.outcome());
        if (result.replay() != null) {
            record = record.with("replay", result.replay().toString());
        }
        audit.record(record);
    }

    private app.platform.sharedkernel.TenantId tenant() {
        return contexts.current().map(TenantContext::tenantId).orElse(null);
    }
}
