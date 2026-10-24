package app.platform.sharedkernel.approval;

import java.util.UUID;

/**
 * Whether an approval process applies to a record (FA6A, ADR-0196): a yes or no and nothing else. A module that owns an
 * object and has a way to finish its work without a review when the organization has set none up asks it, so that the
 * way without a review is never a way round a process that exists. The approval module implements it; the asker never
 * depends on {@code approval}.
 *
 * <p>The answer is the engine's own choice of process: the plan has the approvals feature and at least one active
 * process of the object whose entry criteria are not false for the record as it is now. A process whose criteria cannot
 * be evaluated counts as applying, because the person is then told so when they submit, not let past it.
 * Implementations answer for the organization of the thread's tenant context, in the caller's transaction.
 */
public interface ApprovalApplicability {

    /** Whether a submit of the record would be offered a process, or be refused for one that cannot be judged. */
    boolean applies(String objectApiName, UUID recordId);
}
