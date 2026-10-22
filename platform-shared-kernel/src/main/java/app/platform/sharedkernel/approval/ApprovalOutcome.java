package app.platform.sharedkernel.approval;

/**
 * How an approval request ended, as a hook is told (FA4A, ADR-0187). It has one more member than the outcomes an
 * administrator can attach field updates to: an administrator's cancellation is an end like the others, and a hook that
 * keeps something outside the record in step with the request must see it.
 */
public enum ApprovalOutcome {
    /** The last step was approved (or every step after the last approval was skipped). */
    APPROVED,
    /** A person rejected a step. */
    REJECTED,
    /** The person who submitted withdrew the request, or a member who may override the lock did. */
    RECALLED,
    /** A member who may override the lock ended the request. */
    CANCELLED
}
