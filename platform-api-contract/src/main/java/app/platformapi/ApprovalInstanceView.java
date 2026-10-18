package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * One approval request in full (Sprint 24, ADR-0145): its steps with the people asked, what the caller may do now and,
 * for the people who may see it, the history.
 *
 * @param summary the request in short
 * @param steps the steps in order
 * @param actions what the caller may do now
 * @param historyVisible whether the caller may see the history (the submitter, anyone who was asked, and members who
 *        hold {@code approvals.view-all}); when false, {@code history} is empty
 * @param history the history, oldest first
 */
public record ApprovalInstanceView(@NotNull ApprovalInstanceSummaryView summary, @NotNull List<ApprovalStepView> steps,
        @NotNull ApprovalActionsView actions, @NotNull Boolean historyVisible,
        @NotNull List<ApprovalHistoryView> history) {

    /** Copies the lists. */
    public ApprovalInstanceView {
        steps = List.copyOf(steps);
        history = List.copyOf(history);
    }
}
