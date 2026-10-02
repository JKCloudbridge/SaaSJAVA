package app.platform.licensing;

import java.time.Instant;

/**
 * A change of a subscription by a platform administrator; every field is optional (null leaves it as it is).
 *
 * @param planKey move to this plan; the pools take the new plan's quantities (refused when one would fall below use)
 * @param status the new status
 * @param trialEndsAt the new end of the trial (required when the status becomes TRIAL)
 * @param periodEndsAt the end of the paid period
 */
public record SubscriptionChange(String planKey, String status, Instant trialEndsAt, Instant periodEndsAt) {
}
