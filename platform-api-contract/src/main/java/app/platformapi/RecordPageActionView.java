package app.platformapi;

import jakarta.validation.constraints.NotNull;

/**
 * One action a record page offers (Sprint 20): {@code EDIT}, {@code DELETE}, {@code CLONE} or {@code SHARE} (opens
 * the record's own sharing, Sprint 17/17A), already decided for the caller and, when a record is open, for that
 * record. A convenience only: the action's own endpoint decides again when it is used.
 *
 * @param action {@code EDIT}, {@code DELETE}, {@code CLONE} or {@code SHARE}
 * @param label the text to show, chosen by the administrator or the platform's own word for the action
 */
public record RecordPageActionView(@NotNull String action, @NotNull String label) {
}
