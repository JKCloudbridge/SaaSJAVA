package app.platformapi;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Invites a new first administrator for an organization that is being set up, or that lost every administrator.
 *
 * @param email the address to invite
 */
public record FirstAdministratorRequest(@NotBlank @Size(max = 254) String email) {

    @Override
    public String toString() {
        return "FirstAdministratorRequest[redacted]";
    }
}
