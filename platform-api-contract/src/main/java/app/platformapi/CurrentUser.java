package app.platformapi;

import jakarta.validation.constraints.NotNull;

/**
 * Who the caller is, as far as the platform knows after authentication (Sprint 3). Authorization (what the caller
 * may do in an organization) is decided elsewhere and is not part of this answer.
 *
 * @param id the user's identifier
 * @param email the address the user signs in with
 * @param displayName the name for people
 */
public record CurrentUser(@NotNull String id, @NotNull String email, @NotNull String displayName) {
}
