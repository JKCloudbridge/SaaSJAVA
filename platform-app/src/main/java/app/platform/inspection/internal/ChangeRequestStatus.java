package app.platform.inspection.internal;

import java.util.Optional;

/**
 * The status of a change request and the moves between them (FA5, ADR-0191). The database enforces the same table
 * (V067), so a mistake here cannot write a move the lifecycle lacks.
 *
 * <ul>
 *   <li>{@code OPEN}: asked, nobody has answered. It can be accepted, declined or turned into a version.</li>
 *   <li>{@code ACCEPTED}: the owner agreed and it waits to be made. It can be declined or turned into a version.</li>
 *   <li>{@code DECLINED}: the owner said no, with a reason. Final.</li>
 *   <li>{@code DONE}: turned into a draft version. It can go back to open when that version was rejected or withdrawn,
 *   so that the comments are not lost with it.</li>
 * </ul>
 */
enum ChangeRequestStatus {

    OPEN, ACCEPTED, DECLINED, DONE;

    /** Whether a comment may be added to a request in this status. */
    boolean takesComments() {
        return this == OPEN || this == ACCEPTED;
    }

    /** Whether the request may be turned into a new version from this status. */
    boolean canBeTurned() {
        return this == OPEN || this == ACCEPTED;
    }

    /** Whether the request may move to the other status. */
    boolean canMoveTo(ChangeRequestStatus to) {
        return switch (this) {
            case OPEN -> to == ACCEPTED || to == DECLINED || to == DONE;
            case ACCEPTED -> to == DECLINED || to == DONE;
            case DONE -> to == OPEN;
            case DECLINED -> false;
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
