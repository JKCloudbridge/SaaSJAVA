package app.platformapi;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The end of a password reset: the token from the mailed link and the new password. The text form never shows either.
 *
 * @param token the token from the link (the part after the {@code #})
 * @param newPassword the new password; the server decides whether it is acceptable
 */
public record ResetPasswordRequest(
        @NotBlank @Size(max = 200) String token,
        @NotBlank @Size(max = 1024) String newPassword) {

    @Override
    public String toString() {
        return "ResetPasswordRequest[redacted]";
    }
}
