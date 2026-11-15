package app.platform.sharedkernel.approval;

import java.util.Set;
import java.util.UUID;

/**
 * The people chosen to review a record, when that choice was made with the publication of the record's document
 * (MC2A, ADR-0260). It is the seam behind the approver rule {@code PUBLICATION_REVIEWERS}: the approval module asks it,
 * when a step becomes active, who the reviewers are for one record; the module that owns the choice implements it.
 * Neither depends on the other, the same inversion as {@link ApprovalHooks} and {@link RecordLocks}.
 *
 * <p>The answer is the choice as it is at that moment: the people and the groups named, in the organization of the
 * thread's tenant context, read live in the caller's transaction. A group is returned as a group; the approval module
 * expands it to its members when it works out who is asked, so a person who joins the group later is not added to a
 * step already active (ADR-0146 decision 3). Nothing here says whether anyone may decide: the approval module checks
 * that the person is an active member who holds the licence their profile needs, and the engine's own rules (the
 * submitter is left out unless the process allows it) apply as for every approver.
 */
public interface PublicationReviewers {

    /**
     * The people and groups named for one record.
     *
     * @param userIds the people named one by one
     * @param groupIds the public groups named
     */
    record Reviewers(Set<UUID> userIds, Set<UUID> groupIds) {

        /** Copies the sets. */
        public Reviewers {
            userIds = Set.copyOf(userIds);
            groupIds = Set.copyOf(groupIds);
        }

        /** Nobody is named. */
        public static Reviewers none() {
            return new Reviewers(Set.of(), Set.of());
        }

        /** Prints counts only: the identifiers are the organization's. */
        @Override
        public String toString() {
            return "Reviewers[" + userIds.size() + " people, " + groupIds.size() + " groups]";
        }
    }

    /** Whether this implementation answers for records of the object. */
    boolean serves(String object);

    /**
     * The reviewers named for the record, or none when nothing was chosen for it (a record that belongs to no plant, a
     * plant with no reviewer, an unknown record).
     *
     * @param object the object of the record
     * @param recordId the record
     */
    Reviewers reviewersOf(String object, UUID recordId);
}
