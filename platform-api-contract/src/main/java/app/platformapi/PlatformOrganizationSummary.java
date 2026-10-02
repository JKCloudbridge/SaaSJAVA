package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

/**
 * An organization in the console list. Only what the platform needs to operate it: never members or business data.
 *
 * @param id the organization
 * @param slug the short name (first label of its host)
 * @param displayName the name for people
 * @param status {@code PROVISIONING}, {@code ACTIVE}, {@code SUSPENDED} or {@code DEACTIVATED}
 * @param plan the plan name, absent when the organization has no subscription
 * @param subscriptionStatus the subscription status, absent when there is none
 * @param trialEndsAt when the trial ends, if it is a trial
 * @param trialExpired whether it is a trial whose end has passed
 */
public record PlatformOrganizationSummary(@NotNull UUID id, @NotNull String slug, @NotNull String displayName,
        @NotNull String status, String plan, String subscriptionStatus, Instant trialEndsAt, boolean trialExpired) {
}
