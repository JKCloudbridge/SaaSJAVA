package app.platformapi;

import jakarta.validation.constraints.NotNull;

/**
 * The organization a person just founded.
 *
 * @param slug the organization's short name
 * @param displayName the organization's name for people
 * @param host the host name (with the port, when there is one) at which the organization is reached; the server builds
 *        it, so the browser never composes an organization's address itself
 */
public record OrganizationCreated(@NotNull String slug, @NotNull String displayName, @NotNull String host) {
}
