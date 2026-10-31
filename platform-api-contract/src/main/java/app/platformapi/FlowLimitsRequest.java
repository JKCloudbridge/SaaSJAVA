package app.platformapi;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * The limits an administrator sets for a connection (Sprint 29, ADR-0232). A null leaves the platform's default in
 * force; a number above the platform's ceiling is refused.
 *
 * @param ratePerMinute sends the connection may start in a minute
 * @param maxConcurrent sends of the connection that may be under way at once
 */
public record FlowLimitsRequest(@Min(1) @Max(600000) Integer ratePerMinute, @Min(1) @Max(256) Integer maxConcurrent) {
}
