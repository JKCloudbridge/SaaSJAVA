package app.platformapi;

import jakarta.validation.constraints.NotNull;

/**
 * The answer to sending a verification ping (Sprint 32, ADR-0276): whether the address answered with a success within
 * the deadline, in a stable reason and the platform's own sentence. Nothing the other side said is kept or shown.
 *
 * @param delivered whether the address answered 2xx in time
 * @param reason {@code ok} or the stable key of what went wrong
 * @param statusClass {@code 2xx}, {@code 3xx}, {@code 4xx}, {@code 5xx} or {@code none}
 * @param message the platform's own sentence
 * @param verified whether the subscription is now verified for the address as it stands
 */
public record WebhookTestView(boolean delivered, @NotNull String reason, @NotNull String statusClass,
        @NotNull String message, boolean verified) {
}
