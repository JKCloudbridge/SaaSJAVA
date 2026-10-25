package app.platform.sharedkernel.approval;

import java.util.UUID;

/**
 * Starting an approval for a record on behalf of a person, from a module that owns the object and must not depend on
 * {@code approval} (FA6E, ADR-0199). The change requests of a shipped solution use it: raising a request
 * makes a small record of its own and sends that record to the process the organization has set up for it, in the same
 * transaction, so a request is never left without its review.
 *
 * <p>The engine chooses the process (the only active one of the object whose entry criteria the record meets); the
 * asker names none. The person is the submitter, so the rules that keep a person from approving what they submitted
 * apply to them. Implementations answer for the organization of the thread's tenant context, inside the caller's
 * transaction, and must be called with the record already stored.
 */
public interface ApprovalStarter {

    /**
     * Whether a start for this object can be offered at all: the plan has the approvals feature and the object has at
     * least one active process. An asker that has a way to finish its work without a review when the organization set
     * none up asks this first, so that way is never a way round a process that exists.
     */
    boolean available(String objectApiName);

    /**
     * Starts the request.
     *
     * @param objectApiName the object
     * @param recordId the record, which must exist
     * @param submitter the user who asked for the review
     * @return the identifier of the approval request
     * @throws ApprovalStartRefusal when the request cannot be started, with fixed words for the person; nothing is
     *         created
     */
    UUID startForPerson(String objectApiName, UUID recordId, UUID submitter);
}
