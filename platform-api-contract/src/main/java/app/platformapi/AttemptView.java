package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/**
 * One attempt to send (Sprint 29, ADR-0234): when it began, how long it took, how it ended and why, in a stable reason
 * and a class of status. Never a payload, a header, an address or an identifier of the other system.
 *
 * @param generation 1, and one more each time the delivery was sent again by an administrator
 * @param attempt the number of the attempt in its generation
 * @param startedAt when it began
 * @param durationMs how long it took, in milliseconds
 * @param outcome how it ended: success, accepted for later, to be tried again, permanent failure or interrupted
 * @param reason a stable key, or null
 * @param words the platform's own sentence for the reason, or null
 * @param statusClass {@code 2xx} to {@code 5xx}, or {@code none} when nothing was answered
 * @param httpStatus the status the other side answered with, or null
 */
public record AttemptView(@NotNull Integer generation, @NotNull Integer attempt, @NotNull Instant startedAt,
        @NotNull Integer durationMs, @NotNull String outcome, String reason, String words,
        @NotNull String statusClass, Integer httpStatus) {
}
