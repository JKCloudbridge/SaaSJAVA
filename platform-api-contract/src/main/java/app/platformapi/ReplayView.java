package app.platformapi;

import jakarta.validation.constraints.NotNull;

/**
 * The result of sending dead letters again (Sprint 29, ADR-0231).
 *
 * @param replayed how many were put back in the queue
 * @param remaining how many dead letters are still set aside
 */
public record ReplayView(@NotNull Integer replayed, @NotNull Long remaining) {
}
