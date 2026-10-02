package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A member of the organization as the people who may see members see them.
 *
 * @param id the membership (not the person's account)
 * @param email the member's address
 * @param displayName the member's name
 * @param status {@code ACTIVE} or {@code DEACTIVATED}
 * @param foundingAdministrator the historical fact that this person created the organization; grants nothing
 * @param since when the person became a member
 * @param you whether this member is the caller (for presentation only)
 * @param licence the key of the licence type the member holds for their profile, absent when they hold none
 * @param profileId the member's profile, absent for a deactivated member who has none
 * @param profileName the profile's name
 * @param licensed whether the member holds the licence their profile needs; when not, the profile gives no abilities
 * @param roleId the member's role, absent for none
 * @param roleName the role's name
 * @param policies the member's access policies
 */
public record MemberView(@NotNull UUID id, @NotNull String email, @NotNull String displayName, @NotNull String status,
        boolean foundingAdministrator, @NotNull Instant since, boolean you, String licence, UUID profileId,
        String profileName, boolean licensed, UUID roleId, String roleName, @NotNull List<AccessPolicyRef> policies) {

    public MemberView {
        policies = policies == null ? List.of() : List.copyOf(policies);
    }

    @Override
    public String toString() {
        return "MemberView[redacted]";
    }
}
