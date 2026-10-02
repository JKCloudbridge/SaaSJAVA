package app.platformapi;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Asks to continue in another organization of the caller. The slug is a destination, not the caller's tenant: the
 * server
 * checks it against the caller's own memberships and answers the same for an unknown organization and one the caller
 * does not belong to.
 *
 * @param slug the short name of the organization to go to, as the switcher listed it
 */
public record SwitchOrganizationRequest(@NotBlank @Size(max = 40) String slug) {
}
