package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;

/**
 * Where the circuit breaker of a connection stands (Sprint 29, ADR-0229) and what an administrator may do about it.
 *
 * @param state {@code CLOSED}, {@code OPEN} or {@code HALF_OPEN}
 * @param words the platform's own sentence about the state
 * @param forced {@code OPEN} or {@code CLOSED} when an administrator forced it, or null for automatic
 * @param consecutiveFailures failures in a row since the last sign of life
 * @param openUntil when an open circuit lets a probe through, or null
 * @param openedAt when it last opened, or null
 * @param lastSuccessAt when a send last succeeded, or null
 * @param lastFailureAt when a send last failed, or null
 * @param lastFailureReason the stable key of the last failure, or null
 * @param lastFailureWords the platform's own sentence for it, or null
 * @param actions what the caller may do now, each with the words that say what it does; empty without the right
 */
public record CircuitView(@NotNull String state, @NotNull String words, String forced,
        @NotNull Integer consecutiveFailures, Instant openUntil, Instant openedAt, Instant lastSuccessAt,
        Instant lastFailureAt, String lastFailureReason, String lastFailureWords,
        @NotNull List<CircuitAction> actions) {

    public CircuitView {
        actions = actions == null ? List.of() : List.copyOf(actions);
    }

    /**
     * Something that can be done to the circuit.
     *
     * @param key what to send back
     * @param label the words on the button
     * @param effect what happens, in a sentence for the confirmation
     */
    public record CircuitAction(@NotNull String key, @NotNull String label, @NotNull String effect) {
    }
}
