package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/**
 * The subscription of an organization: a plan, a status and dates. No payment is involved.
 *
 * @param planKey the plan key
 * @param planName the plan name for people
 * @param status {@code TRIAL}, {@code ACTIVE}, {@code SUSPENDED} or {@code CANCELLED}
 * @param startedAt when it began
 * @param trialEndsAt when the trial ends, if it is a trial
 * @param periodEndsAt the end of the paid period, if recorded
 * @param trialExpired whether it is a trial whose end has passed (nothing switches off by itself)
 */
public record SubscriptionInfo(@NotNull String planKey, @NotNull String planName, @NotNull String status,
        @NotNull Instant startedAt, Instant trialEndsAt, Instant periodEndsAt, boolean trialExpired) {
}
