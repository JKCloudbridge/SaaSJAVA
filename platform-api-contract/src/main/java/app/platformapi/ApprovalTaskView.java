package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

/**
 * One line of an approver's inbox (Sprint 24): a task and what it is about.
 *
 * @param assignmentId the task
 * @param instance the request, in short
 * @param objectLabel the label of the object, or null when the caller cannot read the object
 * @param recordTitle the title of the record when the caller can read it, otherwise null
 * @param recordReadable whether the caller can open the record; when false the screen says "you cannot open this
 *        record" and offers no link (an approval grants no access, ADR-0146)
 * @param stepLabel the label of the step the task is on
 * @param via how the caller came to hold the task
 * @param forPerson for a delegated task the person the caller acts for
 * @param status {@code OPEN}, {@code APPROVED}, {@code REJECTED}, {@code REASSIGNED} or {@code CLOSED}
 * @param since when the task was created
 * @param decidedAt when the caller decided, or null
 * @param actions what the caller may do with this task now
 */
public record ApprovalTaskView(@NotNull UUID assignmentId, @NotNull ApprovalInstanceSummaryView instance,
        String objectLabel, String recordTitle, @NotNull Boolean recordReadable, @NotNull String stepLabel,
        @NotNull String via, ApprovalPersonView forPerson, @NotNull String status, @NotNull Instant since,
        Instant decidedAt, @NotNull ApprovalActionsView actions) {

    @Override
    public String toString() {
        return "ApprovalTaskView[" + status + "]";
    }
}
