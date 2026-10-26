package app.platform.inspection.internal;

import java.util.Optional;

/**
 * The status of a change request and the moves between them (FA5, ADR-0191; changed by FA8, ADR-0202). The database
 * enforces the same table (V067, V075), so a mistake here cannot write a move the lifecycle lacks.
 *
 * <ul>
 *   <li>{@code OPEN}: asked and in progress. Its approval is deciding it; it is published when the last step ends well,
 *   and declined when any step rejects it. Only one request is open on a template at a time.</li>
 *   <li>{@code DECLINED}: a step of the approval rejected it, or an administrator cancelled it. Final.</li>
 *   <li>{@code DONE}: published. The revised sheet is the revision in use. Final.</li>
 *   <li>{@code ACCEPTED}: left from the earlier way of working, where an accepted request waited to be turned into a
 *   version. Nothing moves a request into it any more; a request that is in it is read as still waiting and can only
 *   be declined or published.</li>
 * </ul>
 */
enum ChangeRequestStatus {

    OPEN, ACCEPTED, DECLINED, DONE;

    /** Whether a comment may be added to a request in this status. */
    boolean takesComments() {
        return this == OPEN || this == ACCEPTED;
    }

    /** Whether the request is still in progress: it is counted as waiting and blocks another one. */
    boolean inProgress() {
        return this == OPEN || this == ACCEPTED;
    }

    /** Whether the request may move to the other status. */
    boolean canMoveTo(ChangeRequestStatus to) {
        return switch (this) {
            case OPEN -> to == DECLINED || to == DONE;
            case ACCEPTED -> to == DECLINED || to == DONE;
            case DONE, DECLINED -> false;
        };
    }

    /** The status with the stored name, or empty. */
    static Optional<ChangeRequestStatus> of(String name) {
        if (name == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(name.strip().toUpperCase(java.util.Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
