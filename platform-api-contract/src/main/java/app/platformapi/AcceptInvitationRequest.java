package app.platformapi;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * A person without an account accepts an invitation: the token from the link, the password they choose and, when the
 * administrator who invited them did not enter one, their name. The text form never shows the token or the password.
 *
 * @param token the token from the link
 * @param displayName the person's name; required only when the invitation holds none (the preview says so)
 * @param password the new password; the server decides whether it is acceptable
 */
public record AcceptInvitationRequest(
        @NotBlank @Size(max = 200) String token,
        @Size(max = 200) String displayName,
        @NotBlank @Size(max = 1024) String password) {

    @Override
    public String toString() {
        return "AcceptInvitationRequest[redacted]";
    }
}
