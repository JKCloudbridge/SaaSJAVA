package app.platformapi;

import jakarta.validation.constraints.NotNull;

/**
 * What the header needs to know about approvals for the signed-in member (Sprint 24, ADR-0152): whether to show the
 * link
 * to the inbox and how many tasks wait. The server decides; the screen shows a link only when there is something behind
 * it.
 *
 * @param openTasks how many tasks the member holds that wait for a decision
 * @param available whether the member has anything to do with approvals: the plan has the feature, or they hold or
 *        held a task, or they submitted a request
 */
public record ApprovalInboxSummaryView(@NotNull Integer openTasks, @NotNull Boolean available) {
}
