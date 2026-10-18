package app.platform.sharedkernel.approval;

/**
 * Whether approvals are under way (Sprint 24, ADR-0152): a process, or an object, that has a pending request is not
 * removed, because a record must not be locked by something nothing can finish. The metadata module asks before it
 * removes
 * a process; the approval module implements it. The same shape as {@code ObjectUsage}.
 *
 * <p>Implementations answer for the organization of the thread's tenant context, in the caller's transaction.
 */
public interface ApprovalUsage {

    /** How many requests are pending under the process. */
    int pendingUnder(String objectApiName, String processApiName);

    /** How many requests are pending for records of the object, under any process. */
    int pendingOn(String objectApiName);
}
