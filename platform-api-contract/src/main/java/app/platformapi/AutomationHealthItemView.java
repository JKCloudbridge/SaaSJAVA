package app.platformapi;

import jakarta.validation.constraints.NotNull;

/**
 * One count in the health of automation (Sprint 25, ADR-0163).
 *
 * @param key a stable name
 * @param label what is counted
 * @param count how many
 * @param needsAttention whether somebody should look at it
 * @param href where to look, a path on the organization's host, or null
 */
public record AutomationHealthItemView(@NotNull String key, @NotNull String label, @NotNull Long count,
        @NotNull Boolean needsAttention, String href) {
}
