package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/**
 * One limit of the public API and how much of it is used now (Sprint 32, ADR-0280). A number only: never a value, a
 * record or a name of anyone.
 *
 * @param key {@code minute} or {@code day}
 * @param title a few words for a person
 * @param limit what is allowed in the window
 * @param used what the window has used so far, as far as the counters know
 * @param remaining what is left, never below zero
 * @param resetsAt when the window ends and the count starts again, or null while nothing has been counted
 * @param overridden whether the platform set this number for the organization instead of the default
 */
public record LimitUsageView(@NotNull String key, @NotNull String title, long limit, long used, long remaining,
        Instant resetsAt, boolean overridden) {
}
