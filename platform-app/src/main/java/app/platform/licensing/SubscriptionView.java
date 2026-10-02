package app.platform.licensing;

import java.time.Instant;

/**
 * An organization's subscription (ADR-0033): a plan, a status and dates. No payment processor is involved.
 *
 * @param planKey the plan
 * @param planName the plan's name for people
 * @param status {@code TRIAL}, {@code ACTIVE}, {@code SUSPENDED} or {@code CANCELLED}
 * @param startedAt when it began
 * @param trialEndsAt when the trial ends (set while the status is TRIAL; a trial that is over is only shown as
 *        expired, nothing switches off by itself before Sprint 25)
 * @param periodEndsAt the end of the paid period, if one is recorded
 */
public record SubscriptionView(String planKey, String planName, String status, Instant startedAt,
        Instant trialEndsAt, Instant periodEndsAt) {

    /** Whether the subscription is a trial whose end has passed at {@code now}. */
    public boolean trialExpired(Instant now) {
        return "TRIAL".equals(status) && trialEndsAt != null && !trialEndsAt.isAfter(now);
    }
}
