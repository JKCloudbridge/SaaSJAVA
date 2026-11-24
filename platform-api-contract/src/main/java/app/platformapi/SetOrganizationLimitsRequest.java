package app.platformapi;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Sets the limits on the public API of one organization (Sprint 32, ADR-0283). A number left out keeps the default. The
 * numbers can only be set inside the ceilings the platform's settings state.
 *
 * @param organizationPerMinute requests a minute for the organization as a whole, or null for the default
 * @param organizationPerDay requests a day for the organization as a whole, or null for the default
 * @param applicationPerMinute requests a minute for each application and each member's token, or null for the default
 * @param reason why, 1 to 200 characters; kept in the audit trail only, so do not put personal data in it
 */
public record SetOrganizationLimitsRequest(@Min(1) @Max(10_000_000) Integer organizationPerMinute,
        @Min(1) @Max(1_000_000_000) Long organizationPerDay, @Min(1) @Max(10_000_000) Integer applicationPerMinute,
        @NotBlank @Size(max = 200) String reason) {

    @Override
    public String toString() {
        // The reason is free text typed by a platform person: it stays out of any log line that prints this record.
        return "SetOrganizationLimitsRequest[redacted]";
    }
}
