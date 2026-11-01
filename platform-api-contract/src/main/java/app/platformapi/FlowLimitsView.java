package app.platformapi;

import jakarta.validation.constraints.NotNull;

/**
 * How fast and how many at once a connection may send (Sprint 29, ADR-0232), with the platform's ceilings.
 *
 * @param ratePerMinute sends the connection may start in a minute
 * @param maxConcurrent sends of the connection that may be under way at once
 * @param ratePerMinuteIsDefault whether the rate is the platform's default
 * @param maxConcurrentIsDefault whether the concurrency is the platform's default
 * @param ratePerMinuteCeiling the highest rate an administrator may set
 * @param maxConcurrentCeiling the highest concurrency an administrator may set
 */
public record FlowLimitsView(@NotNull Integer ratePerMinute, @NotNull Integer maxConcurrent,
        @NotNull Boolean ratePerMinuteIsDefault, @NotNull Boolean maxConcurrentIsDefault,
        @NotNull Integer ratePerMinuteCeiling, @NotNull Integer maxConcurrentCeiling) {
}
