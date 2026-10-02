package app.platformapi;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * A signed-in person founds an organization.
 *
 * @param displayName the organization's name for people
 * @param slug the organization's short name, the first label of its host name; the server checks the format and the
 *        reserved names and decides whether it is free
 */
public record CreateOrganizationRequest(
        @NotBlank @Size(max = 200) String displayName,
        @NotBlank @Size(max = 100) String slug) {
}
