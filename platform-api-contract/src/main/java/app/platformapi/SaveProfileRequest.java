package app.platformapi;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Creates a profile or changes one (Sprint 7). The text is typed by a person, so it never prints.
 *
 * @param name the name, unique in the organization
 * @param description what the profile is for, optional
 * @param licenceType the key of the licence type a member needs to use the profile (cannot change while members use
 *        the profile)
 * @param abilities the ability keys; unknown keys are refused
 */
public record SaveProfileRequest(
        @NotBlank @Size(max = 80) String name,
        @Size(max = 500) String description,
        @NotBlank @Size(max = 40) String licenceType,
        @NotNull @Size(max = 100) List<@NotBlank @Size(max = 60) String> abilities) {

    public SaveProfileRequest {
        // A null element in the text of a request is dropped, not a server error; the copy is unmodifiable.
        abilities = abilities == null ? null
                : List.copyOf(abilities.stream().filter(java.util.Objects::nonNull).toList());
    }

    @Override
    public String toString() {
        return "SaveProfileRequest[redacted]";
    }
}
