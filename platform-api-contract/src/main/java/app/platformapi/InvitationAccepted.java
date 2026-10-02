package app.platformapi;

import jakarta.validation.constraints.NotNull;

/**
 * The organization a person just joined.
 *
 * @param slug the organization's short name
 * @param displayName the organization's name for people
 * @param host the host name (with the port, when there is one) at which to sign in; built by the server
 */
public record InvitationAccepted(@NotNull String slug, @NotNull String displayName, @NotNull String host) {
}
