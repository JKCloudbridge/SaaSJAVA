package app.platformapi;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * A person without an account accepts an invitation: the token from the link, and the name and password they choose.
 * The text form never shows the token or the password.
 *
 * @param token the token from the link
 * @param displayName the person's name
 * @param password the new password; the server decides whether it is acceptable
 */
public record AcceptInvitationRequest(
        @NotBlank @Size(max = 200) String token,
        @NotBlank @Size(max = 200) String displayName,
        @NotBlank @Size(max = 1024) String password) {

    @Override
    public String toString() {
        return "AcceptInvitationRequest[redacted]";
    }
}
