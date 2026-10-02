package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

/**
 * A profile of the organization: the base set of abilities of a member, belonging to one licence type (Sprint 7).
 *
 * @param id the profile
 * @param name the name chosen by the organization
 * @param description what the profile is for
 * @param licenceType the key of the licence type a member needs to use the profile
 * @param abilities the ability keys of the profile; for a profile with full access this lists every ability the
 *        platform knows
 * @param system whether the platform made it: a system profile cannot be removed
 * @param fullAccess whether the profile always holds every ability (the administrator profile); it cannot be edited
 * @param defaultProfile whether new members get it unless an administrator chooses another
 * @param members how many members hold it
 */
public record ProfileView(@NotNull UUID id, @NotNull String name, @NotNull String description,
        @NotNull String licenceType, @NotNull List<String> abilities, boolean system, boolean fullAccess,
        boolean defaultProfile, int members) {

    public ProfileView {
        abilities = abilities == null ? List.of() : List.copyOf(abilities);
    }

    @Override
    public String toString() {
        return "ProfileView[redacted]";
    }
}
