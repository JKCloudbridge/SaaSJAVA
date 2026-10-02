package app.platformapi;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/**
 * Changes the subscription of an organization. Every field but the reason is optional (absent leaves it as it is).
 *
 * @param planKey move to this plan; the licence pools take its quantities (refused when one would fall below use)
 * @param status {@code TRIAL}, {@code ACTIVE}, {@code SUSPENDED} or {@code CANCELLED}
 * @param trialEndsAt the end of the trial (required when the status becomes TRIAL and none is set)
 * @param periodEndsAt the end of the paid period
 * @param reason why, 1 to 200 characters, kept in the audit trail
 */
public record ChangeSubscriptionRequest(@Size(max = 40) String planKey, @Size(max = 20) String status,
        Instant trialEndsAt, Instant periodEndsAt, @NotBlank @Size(max = 200) String reason) {

    @Override
    public String toString() {
        // The reason is free text typed by a platform person: it stays out of any log line that prints this record.
        return "ChangeSubscriptionRequest[redacted]";
    }
}
