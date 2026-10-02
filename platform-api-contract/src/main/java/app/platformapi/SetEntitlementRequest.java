package app.platformapi;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Switches a feature on or off for one organization over its plan, or goes back to the plan.
 *
 * @param enabled true or false to override the plan, absent to remove the override
 * @param reason why, 1 to 200 characters, kept in the audit trail
 */
public record SetEntitlementRequest(Boolean enabled, @NotBlank @Size(max = 200) String reason) {

    @Override
    public String toString() {
        // The reason is free text typed by a platform person: it stays out of any log line that prints this record.
        return "SetEntitlementRequest[redacted]";
    }
}
