package app.platformapi;

import jakarta.validation.constraints.NotNull;

/**
 * One kind of event a webhook may ask for (Sprint 32, ADR-0275). The list is closed and lives on the server; a screen
 * draws its choices from it and holds no list of its own.
 *
 * @param key the stable name of the event, as it is sent in the {@code type} of a delivery
 * @param title a few words for a person
 * @param description what makes the event happen
 */
public record WebhookEventView(@NotNull String key, @NotNull String title, @NotNull String description) {
}
