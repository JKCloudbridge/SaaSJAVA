package app.platformapi;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Creates an access policy or changes one (Sprint 7). The text is typed by a person, so it never prints.
 *
 * @param name the name, unique in the organization
 * @param description what the policy is for, optional
 * @param abilities the ability keys it adds; unknown keys are refused
 * @param requiredLicenceType the key of the licence type that assigning the policy uses, absent for none (cannot
 *        change while the policy is assigned)
 */
public record SaveAccessPolicyRequest(
        @NotBlank @Size(max = 80) String name,
        @Size(max = 500) String description,
        @NotNull @Size(max = 100) List<@NotBlank @Size(max = 60) String> abilities,
        @Size(max = 40) String requiredLicenceType) {

    public SaveAccessPolicyRequest {
        // A null element in the text of a request is dropped, not a server error; the copy is unmodifiable.
        abilities = abilities == null ? null
                : List.copyOf(abilities.stream().filter(java.util.Objects::nonNull).toList());
    }

    @Override
    public String toString() {
        return "SaveAccessPolicyRequest[redacted]";
    }
}
