package app.platformapi;

import jakarta.validation.constraints.NotNull;

/**
 * One placeholder an administrator may use in the text of a notice (Sprint 25, ADR-0155).
 *
 * @param name the name, written in braces in the text
 * @param help what it stands for
 */
public record NotificationPlaceholderView(@NotNull String name, @NotNull String help) {
}
