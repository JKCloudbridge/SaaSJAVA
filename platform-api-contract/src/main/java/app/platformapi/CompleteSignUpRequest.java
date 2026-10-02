package app.platformapi;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The end of a sign-up: the token from the mailed link, and what the person chooses. The text form never shows the
 * token or the password.
 *
 * @param token the token from the link (the part after the {@code #})
 * @param displayName the person's name
 * @param password the new password; the server decides whether it is acceptable
 */
public record CompleteSignUpRequest(
        @NotBlank @Size(max = 200) String token,
        @NotBlank @Size(max = 200) String displayName,
        @NotBlank @Size(max = 1024) String password) {

    @Override
    public String toString() {
        return "CompleteSignUpRequest[redacted]";
    }
}
