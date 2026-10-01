package app.platformapi;

import jakarta.validation.constraints.NotNull;

/**
 * What a browser may learn about the organization addressed by the host name: enough to brand a sign-in page.
 * No identifier is included; the tenant is always derived on the server and never named by the client.
 *
 * @param slug the organization's short name, the first label of its host name
 * @param displayName the organization's name for people
 */
public record TenantSummary(@NotNull String slug, @NotNull String displayName) {
}
