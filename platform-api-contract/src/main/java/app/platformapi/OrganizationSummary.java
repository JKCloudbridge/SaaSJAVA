package app.platformapi;

import jakarta.validation.constraints.NotNull;

/**
 * An organization the signed-in person is an active member of (the organization switcher).
 *
 * @param slug the organization's short name
 * @param displayName the organization's name for people
 * @param host the host name (with the port, when there is one) at which the organization is reached; built by the
 * server
 */
public record OrganizationSummary(@NotNull String slug, @NotNull String displayName, @NotNull String host) {
}
