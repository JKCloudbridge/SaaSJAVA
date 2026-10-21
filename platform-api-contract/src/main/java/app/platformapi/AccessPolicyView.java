package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

/**
 * An access policy of the organization: abilities added to the members it is assigned to (Sprint 7).
 *
 * @param id the policy
 * @param name the name chosen by the organization
 * @param description what the policy is for
 * @param abilities the ability keys it adds
 * @param requiredLicenceType the key of the licence type that assigning it uses, or absent when it needs none
 * @param members how many members it is assigned to
 * @param groups how many groups it is given to (a policy that needs a licence cannot be given to a group)
 * @param managed true for a policy that comes with the platform (Functional track FA1): it can be given to members and
 *        groups and nothing else; it cannot be changed or removed, and the screen offers neither
 */
public record AccessPolicyView(@NotNull UUID id, @NotNull String name, @NotNull String description,
        @NotNull List<String> abilities, String requiredLicenceType, int members, int groups,
        @NotNull Boolean managed) {

    public AccessPolicyView {
        abilities = abilities == null ? List.of() : List.copyOf(abilities);
        managed = managed != null && managed;
    }

    /** A policy an administrator made. */
    public AccessPolicyView(UUID id, String name, String description, List<String> abilities,
            String requiredLicenceType, int members, int groups) {
        this(id, name, description, abilities, requiredLicenceType, members, groups, Boolean.FALSE);
    }

    @Override
    public String toString() {
        return "AccessPolicyView[redacted]";
    }
}
