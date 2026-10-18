package app.platformapi;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Hands the caller's own task to one other person (Sprint 24, ADR-0147), or, for a member who holds
 * {@code approvals.manage}, assigns a person to the active step. Never to more than one person, never back to a person
 * who
 * held the task. The comment is text of a person: bounded, never logged.
 *
 * @param toUserId the person who is asked instead (reassign) or as well (assign)
 * @param version the version of the request the caller last read
 * @param comment an optional note
 */
public record ApprovalReassignRequest(@NotNull UUID toUserId, @NotNull Long version, @Size(max = 2000) String comment) {

    @Override
    public String toString() {
        return "ApprovalReassignRequest[redacted]";
    }
}
