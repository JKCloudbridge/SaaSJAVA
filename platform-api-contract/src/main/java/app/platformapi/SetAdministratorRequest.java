package app.platformapi;

import jakarta.validation.constraints.NotNull;

/**
 * Sets or clears the administrator marker of a member.
 *
 * @param administrator the new value
 */
public record SetAdministratorRequest(@NotNull Boolean administrator) {
}
