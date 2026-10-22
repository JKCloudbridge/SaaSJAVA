package app.platform.sharedkernel.approval;

import java.util.UUID;

/**
 * The one question a module that keeps something under review may ask about the people asked to decide (FA4,
 * ADR-0189): "does this person hold an open task of this request?" It lets the module show the person what they are
 * asked to approve, and nothing else. It is the same inversion as {@link RecordLocks}: the approval module implements
 * it, the module that owns the reviewed thing asks, and neither depends on the other.
 *
 * <p>The answer is a plain yes or no for a request that is still pending: a person whose task was decided, whose
 * request ended, who was never asked, and a request that does not exist all get "no", so nothing is learned about
 * which. An approval grants no access to the record (ADR-0146); what the module does with a "yes" is its own decision
 * and is narrow by design (the version under review, never the record).
 *
 * <p>Answers are for the organization of the thread's tenant context, read live and in the caller's transaction.
 */
public interface ApprovalTasks {

    /**
     * Whether the person has an open task in a request that is pending.
     *
     * @param requestId the approval request
     * @param userId the person, including one who holds the task as another person's delegate
     */
    boolean holdsOpenTask(UUID requestId, UUID userId);
}
