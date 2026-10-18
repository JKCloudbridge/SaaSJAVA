package app.platformapi;

import jakarta.validation.constraints.NotNull;

/**
 * What the caller may do with an approval right now (Sprint 24, ADR-0152): the server's answer, so the screens offer
 * only
 * these and decide nothing. Every request that follows is decided again. A reason is given for the actions that are not
 * offered only where it helps the person (a locked record), never a name.
 *
 * @param canSubmit the caller may submit the record (they may edit it, the feature is on, a process fits and none is
 * pending)
 * @param canApprove the caller holds an open task on the active step
 * @param canReject the same
 * @param canReassign the caller may hand their own task to another person
 * @param canRecall the caller submitted the request or holds the override ability
 * @param canCancel the caller holds the override ability
 * @param canAssign the caller holds the manage ability and the active step may take another person
 * @param canReapply the caller holds the manage ability and the outcome updates were refused
 */
public record ApprovalActionsView(@NotNull Boolean canSubmit, @NotNull Boolean canApprove, @NotNull Boolean canReject,
        @NotNull Boolean canReassign, @NotNull Boolean canRecall, @NotNull Boolean canCancel,
        @NotNull Boolean canAssign,
        @NotNull Boolean canReapply) {

    /** Nothing is offered. */
    public static ApprovalActionsView none() {
        return new ApprovalActionsView(false, false, false, false, false, false, false, false);
    }
}
