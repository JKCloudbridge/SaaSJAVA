package app.platform.approval;

import app.platform.data.BackgroundWrites;
import java.util.UUID;

/**
 * Starting an approval as the platform (Sprint 24, ADR-0150): the narrow public interface through which the workflow
 * module's {@code START_APPROVAL} action submits the record that started a workflow. Everything a person does with an
 * approval (submit, approve, reject, recall, reassign, delegate) goes through the endpoints of this module and is
 * decided
 * by the assignment or the ability of the person; nothing else is public.
 *
 * <p>The start is made in the transaction of the caller (the step of the workflow), so it happens exactly once with its
 * step. It is idempotent: a record that already has a pending request is not submitted again; the call says so. The
 * submitter is the platform, a named actor in the history and nobody for the rules that need a submitter (the exclusion
 * of self-approval). Must be called inside the organization's own tenant context.
 */
public interface Approvals {

    /**
     * What a start did.
     *
     * @param requestId the pending request of the record
     * @param alreadyPending whether the record already had one, so that nothing was started now
     */
    record Started(UUID requestId, boolean alreadyPending) {
    }

    /**
     * Submits the record under the process, as the platform.
     *
     * @param chain the chain of the workflow execution that starts the request, kept on the request so that a loop with
     *        people in it is still seen (ADR-0149)
     * @throws ApprovalStartException with a stable reason when the request cannot be started
     */
    Started startByPlatform(String objectApiName, UUID recordId, String processApiName,
            BackgroundWrites.Chain chain);
}
