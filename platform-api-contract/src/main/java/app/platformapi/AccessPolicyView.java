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
 */
public record AccessPolicyView(@NotNull UUID id, @NotNull String name, @NotNull String description,
        @NotNull List<String> abilities, String requiredLicenceType, int members) {

    public AccessPolicyView {
        abilities = abilities == null ? List.of() : List.copyOf(abilities);
    }

    @Override
    public String toString() {
        return "AccessPolicyView[redacted]";
    }
}
