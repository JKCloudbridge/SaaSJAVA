package app.platformapi;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The current password and the one that replaces it. The text form never shows either.
 *
 * @param currentPassword the password in use now
 * @param newPassword the new password; the server decides whether it is acceptable
 */
public record ChangePasswordRequest(
        @NotBlank @Size(max = 1024) String currentPassword,
        @NotBlank @Size(max = 1024) String newPassword) {

    @Override
    public String toString() {
        return "ChangePasswordRequest[redacted]";
    }
}
