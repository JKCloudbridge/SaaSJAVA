package app.platform.sharedkernel.approval;

import java.util.Collection;
import java.util.UUID;

/**
 * Bringing the people a running review asks up to date with the reviewers chosen for its record (2026-11-17, a note on
 * ADR-0260), from a module that keeps that choice and must not depend on {@code approval}. The reviewers of a plant are
 * read when a review step becomes active ({@link PublicationReviewers}); when the person who chose them changes them,
 * the module calls this in the same transaction, so a fill-in already waiting is asked of the reviewers chosen now.
 *
 * <p>Only the active step of a pending request is touched, and only when that step has a rule that reads the chosen
 * reviewers and needs one approval of the people asked. A person no longer chosen loses their open task; a person newly
 * chosen gets one and is told; a person still chosen keeps theirs. A task a person handed on, or one an administrator
 * added, stays as it is. When nobody is left the request waits for an administrator, as a step with nobody always does.
 * Implementations answer for the organization of the thread's tenant context, inside the caller's transaction, and
 * take the record's row before the request's (the order of the record lock, ADR-0148).
 */
public interface ApprovalRefresh {

    /**
     * Asks the active step of the pending request of each record again.
     *
     * @param objectApiName the object of the records
     * @param recordIds the records; one with no pending request, or whose active step reads no chosen reviewers, is
     *        left alone
     * @param actor the user who changed the reviewers
     * @return how many requests changed
     */
    int refreshChosenApprovers(String objectApiName, Collection<UUID> recordIds, UUID actor);
}
