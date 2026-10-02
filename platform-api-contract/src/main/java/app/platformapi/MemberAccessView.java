package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

/**
 * Everything that decides what one member may do, in one answer (Sprint 7): their profile, their role, their access
 * policies, their individual grants and the resulting abilities. For the people who manage access.
 *
 * @param membershipId the member
 * @param profileId the member's profile, absent for a member without one (a deactivated member)
 * @param profileName the profile's name
 * @param profileLicenceType the licence type the profile needs
 * @param licenceHeld whether the member holds the licence the profile needs; when not, the profile gives no
 *        abilities until a licence is free
 * @param roleId the member's role, absent for none
 * @param roleName the role's name
 * @param policies the member's access policies
 * @param grants the member's individual grants
 * @param abilities the effective abilities (profile while licensed, plus policies, plus grants), sorted
 */
public record MemberAccessView(@NotNull UUID membershipId, UUID profileId, String profileName,
        String profileLicenceType, boolean licenceHeld, UUID roleId, String roleName,
        @NotNull List<AccessPolicyRef> policies, @NotNull List<GrantView> grants, @NotNull List<String> abilities) {

    public MemberAccessView {
        policies = policies == null ? List.of() : List.copyOf(policies);
        grants = grants == null ? List.of() : List.copyOf(grants);
        abilities = abilities == null ? List.of() : List.copyOf(abilities);
    }

    @Override
    public String toString() {
        return "MemberAccessView[redacted]";
    }
}
