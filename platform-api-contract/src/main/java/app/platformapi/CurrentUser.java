package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * Who the caller is, as far as the platform knows after authentication (Sprint 3), and what the caller may do in the
 * organization of the host (Sprint 7).
 *
 * @param id the user's identifier
 * @param email the address the user signs in with
 * @param displayName the name for people
 * @param platformRoles the platform roles the user holds (empty for an ordinary person); for presentation only, every
 *        platform action is decided by the server (Sprint 6)
 * @param abilities the abilities the caller holds in the organization the host names, sorted; empty on the platform
 *        host. For presentation only: every action is decided again by the server
 */
public record CurrentUser(@NotNull String id, @NotNull String email, @NotNull String displayName,
        @NotNull List<String> platformRoles, @NotNull List<String> abilities) {

    public CurrentUser {
        platformRoles = platformRoles == null ? List.of() : List.copyOf(platformRoles);
        abilities = abilities == null ? List.of() : List.copyOf(abilities);
    }
}
