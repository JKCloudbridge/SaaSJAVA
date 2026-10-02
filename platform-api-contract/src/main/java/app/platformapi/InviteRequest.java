package app.platformapi;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * An administrator creates a new member of the organization of the host: the person gets a link to set a password
 * (Sprint 7). The organization is never named here: it is the host the request came to.
 *
 * @param email the address of the person
 * @param displayName the person's name, optional (the person then chooses it when accepting)
 * @param profileId the profile the member gets, absent for the organization's default profile
 * @param roleId the role the member gets, absent for none
 * @param active whether the link is sent now; absent means yes. When no, the invitation is saved and an administrator
 *        sends it later
 */
public record InviteRequest(
        @NotBlank @Size(max = 254) String email,
        @Size(max = 200) String displayName,
        UUID profileId,
        UUID roleId,
        Boolean active) {

    @Override
    public String toString() {
        return "InviteRequest[redacted]";
    }
}
