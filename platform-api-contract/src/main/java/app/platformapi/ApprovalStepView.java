package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;

/**
 * One step of an approval request as it stands (Sprint 24).
 *
 * @param position the place of the step, from 1
 * @param name the name of the step in the process
 * @param label the label people read
 * @param completion {@code ANY} or {@code ALL}
 * @param status {@code WAITING}, {@code ACTIVE}, {@code APPROVED}, {@code REJECTED} or {@code SKIPPED}
 * @param activatedAt when the step became active, or null
 * @param finishedAt when it ended, or null
 * @param assignments the people asked (empty while the step waits, and for an active step nobody can decide)
 */
public record ApprovalStepView(@NotNull Integer position, @NotNull String name, @NotNull String label,
        @NotNull String completion, @NotNull String status, Instant activatedAt, Instant finishedAt,
        @NotNull List<ApprovalAssignmentView> assignments) {

    /** Copies the list. */
    public ApprovalStepView {
        assignments = List.copyOf(assignments);
    }

    @Override
    public String toString() {
        return "ApprovalStepView[" + position + "]";
    }
}
