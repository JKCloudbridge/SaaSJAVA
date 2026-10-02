package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One organization as the console shows it. A platform role gives no view of the members or the business data: this is
 * the state of the organization as a customer of the platform.
 *
 * @param id the organization
 * @param slug the short name
 * @param displayName the name for people
 * @param status where it is in its life
 * @param statusChangedAt when it entered that status
 * @param subscription its subscription, absent when it has none
 * @param pools its licence pools with their numbers
 * @param entitlements every feature with the plan default, the override and the effective answer
 * @param firstAdministrator the state of the first-administrator invitation, absent when none was made
 */
public record PlatformOrganizationDetail(@NotNull UUID id, @NotNull String slug, @NotNull String displayName,
        @NotNull String status, @NotNull Instant statusChangedAt, SubscriptionInfo subscription,
        @NotNull List<LicencePoolView> pools, @NotNull List<EntitlementInfo> entitlements,
        FirstAdministratorInfo firstAdministrator) {

    public PlatformOrganizationDetail {
        pools = List.copyOf(pools);
        entitlements = List.copyOf(entitlements);
    }
}
