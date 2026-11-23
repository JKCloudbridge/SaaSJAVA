package app.platformapi;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * Replaces the signing secret of a webhook subscription (Sprint 32, ADR-0277).
 *
 * @param overlapHours how long the old secret keeps signing as well, so that a receiver can change over without losing
 *        a delivery: 0 ends it at once, at most 24
 */
public record RotateWebhookSecretRequest(@Min(0) @Max(24) int overlapHours) {
}
