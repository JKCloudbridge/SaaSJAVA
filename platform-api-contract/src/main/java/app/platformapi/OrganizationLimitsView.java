package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * The limits on the public API of one organization, for the platform console (Sprint 32, ADR-0283): what is in force
 * and the platform's defaults beside it. Numbers only.
 *
 * @param organizationId the organization
 * @param overridden whether any number differs from the default by the platform's choice
 * @param organizationPerMinute requests a minute for the organization as a whole
 * @param organizationPerDay requests a day for the organization as a whole
 * @param applicationPerMinute requests a minute for each connected application and each member's own token
 * @param defaultOrganizationPerMinute the default of the first
 * @param defaultOrganizationPerDay the default of the second
 * @param defaultApplicationPerMinute the default of the third
 */
public record OrganizationLimitsView(@NotNull UUID organizationId, boolean overridden, int organizationPerMinute,
        long organizationPerDay, int applicationPerMinute, int defaultOrganizationPerMinute,
        long defaultOrganizationPerDay, int defaultApplicationPerMinute) {
}
