package app.platform.workflow.internal;

import app.platform.approval.ApprovalStartException;
import app.platform.approval.Approvals;
import app.platformapi.WorkflowActionTypes;
import org.springframework.stereotype.Component;

/**
 * Submits the record that started the workflow for approval, as the platform (ADR-0150). The step is fire and forget:
 * the instance is started in the step's transaction, so it happens exactly once with its step, and the step completes;
 * the workflow does not wait for the people. What follows the decision is another workflow that starts when the
 * approval
 * ends ({@code APPROVAL_OUTCOME}, ADR-0149).
 *
 * <p>A record that is already being approved is not submitted again: the step completes and says so
 * ({@code already_pending}), so a retry after a crash and a replay never start a second request. Every other reason the
 * request could not start is permanent and stable: nothing a retry does changes it. The chain of this execution travels
 * with the request, so a workflow whose approval ends by starting the same workflow is stopped by the chain rule of
 * ADR-0140 even though people sat between the rounds.
 */
@Component
class StartApprovalAction implements ActionExecutor {

    private final Approvals approvals;
    private final ExecutionStore executions;

    StartApprovalAction(Approvals approvals, ExecutionStore executions) {
        this.approvals = approvals;
        this.executions = executions;
    }

    @Override
    public String type() {
        return WorkflowActionTypes.START_APPROVAL;
    }

    @Override
    public void execute(ActionContext context) {
        String process = context.settings().get("process");
        if (process == null) {
            throw StepFailure.permanent(ApprovalStartException.Reason.PROCESS_MISSING.key());
        }
        try {
            Approvals.Started started = approvals.startByPlatform(context.objectApiName(), context.recordId(), process,
                    context.source().chain());
            if (started.alreadyPending()) {
                executions.noteStep(context.executionId(), context.position(), "already_pending");
            }
        } catch (ApprovalStartException refused) {
            throw StepFailure.permanent(refused.reason().key());
        }
    }
}
