package app.platformapi;

import jakarta.validation.constraints.NotNull;

/**
 * One state a webhook delivery can be in (Sprint 32, ADR-0278), in the words of the platform. The list is closed and lives
 * on the server; a screen draws the filter of the delivery log from it and holds no list of its own.
 *
 * @param key the stable name of the state, as it is used to filter and as the delivery log states it
 * @param title a few words for a person
 * @param description what the state means
 */
public record WebhookStateView(@NotNull String key, @NotNull String title, @NotNull String description) {
}
