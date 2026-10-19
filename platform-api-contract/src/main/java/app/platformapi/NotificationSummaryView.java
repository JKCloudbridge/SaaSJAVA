package app.platformapi;

import jakarta.validation.constraints.NotNull;

/**
 * What the bell in the header needs (Sprint 25, ADR-0154): how many notices the signed-in member has not read. Cheap on
 * purpose, so the screen can ask on focus without waiting for the list.
 *
 * @param unread the number of unread notices
 */
public record NotificationSummaryView(@NotNull Integer unread) {
}
