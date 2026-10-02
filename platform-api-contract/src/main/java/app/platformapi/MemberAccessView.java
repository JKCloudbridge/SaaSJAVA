package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

/**
 * Everything that decides what one member may do, in one answer (Sprint 7, extended in Sprint 8): their profile, their
 * role, their access policies, the groups they are in, their individual grants and the resulting abilities and
 * permissions on data. For the people who manage access.
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
 * @param groups the groups the member is in, directly or through nested groups
 * @param grants the member's individual grants
 * @param abilities the effective abilities (profile while licensed, plus policies, plus the policies of their groups,
 *        plus grants), sorted
 * @param data the effective permissions on objects and fields (the same union, with implied actions written out)
 */
public record MemberAccessView(@NotNull UUID membershipId, UUID profileId, String profileName,
        String profileLicenceType, boolean licenceHeld, UUID roleId, String roleName,
        @NotNull List<AccessPolicyRef> policies, @NotNull List<GroupRef> groups, @NotNull List<GrantView> grants,
        @NotNull List<String> abilities, @NotNull DataAccessView data) {

    public MemberAccessView {
        policies = policies == null ? List.of() : List.copyOf(policies);
        groups = groups == null ? List.of() : List.copyOf(groups);
        grants = grants == null ? List.of() : List.copyOf(grants);
        abilities = abilities == null ? List.of() : List.copyOf(abilities);
        data = data == null ? new DataAccessView(false, List.of(), List.of()) : data;
    }

    @Override
    public String toString() {
        return "MemberAccessView[redacted]";
    }
}
