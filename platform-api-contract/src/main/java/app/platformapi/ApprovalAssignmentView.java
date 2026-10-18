package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

/**
 * One task of an approval step (Sprint 24): a person asked to decide.
 *
 * @param id the task
 * @param assignee the person asked
 * @param via how they came to hold it: {@code DIRECT}, {@code DELEGATED}, {@code FALLBACK}, {@code REASSIGNED} or
 * {@code ADDED}
 * @param forPerson for {@code DELEGATED} the person they act for; for {@code REASSIGNED} the person who handed it over
 * @param status {@code OPEN}, {@code APPROVED}, {@code REJECTED}, {@code REASSIGNED} or {@code CLOSED}
 * @param decidedAt when it was decided, or null
 */
public record ApprovalAssignmentView(@NotNull UUID id, @NotNull ApprovalPersonView assignee, @NotNull String via,
        ApprovalPersonView forPerson, @NotNull String status, Instant decidedAt) {
}
