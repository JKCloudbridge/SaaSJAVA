package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/**
 * One attempt to send a delivery (Sprint 32, ADR-0278): when, how long, how it ended, a stable reason and the class of
 * the answer. Never a header, an address or any part of an answer.
 *
 * @param generation which generation of the delivery it belongs to
 * @param attemptNo the number of the attempt within the generation
 * @param startedAt when it began
 * @param durationMs how long it took
 * @param outcome {@code SUCCESS}, {@code RETRY}, {@code PERMANENT} or {@code INTERRUPTED}
 * @param reason the stable key of the failure, or null
 * @param reasonWords the platform's own sentence for that reason, or null
 * @param statusClass {@code 2xx}, {@code 3xx}, {@code 4xx}, {@code 5xx} or {@code none}
 * @param httpStatus the status number, or null when there was no answer
 */
public record WebhookAttemptView(int generation, int attemptNo, @NotNull Instant startedAt, int durationMs,
        @NotNull String outcome, String reason, String reasonWords, @NotNull String statusClass,
        Integer httpStatus) {
}
