package app.platformapi;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * Sends dead letters again in bulk (Sprint 29, ADR-0231): the oldest first, never more than the limit.
 *
 * @param limit the most to send again, 1 to 500; 100 when absent
 */
public record ReplayRequest(@Min(1) @Max(500) Integer limit) {
}
