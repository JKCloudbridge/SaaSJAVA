package app.platformapi;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * An administrator invites an address into the organization of the host. The organization is never named here: it is
 * the
 * host the request came to.
 *
 * @param email the address to invite
 * @param administrator whether the person becomes an administrator when they accept; absent means no
 */
public record InviteRequest(@NotBlank @Size(max = 254) String email, Boolean administrator) {

    @Override
    public String toString() {
        return "InviteRequest[redacted]";
    }
}
