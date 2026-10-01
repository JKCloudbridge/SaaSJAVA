package app.platformapi;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * An address and a password. The text form never shows the password, so a stray log call cannot leak it.
 *
 * @param email the address, any case
 * @param password the password as typed; never trimmed or changed
 */
public record SignInRequest(
        @NotBlank @Size(max = 320) String email,
        @NotBlank @Size(max = 1024) String password) {

    @Override
    public String toString() {
        return "SignInRequest[redacted]";
    }
}
