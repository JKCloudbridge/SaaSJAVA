package app.platformapi;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * A request for a password-reset link.
 *
 * @param email the address of the account, any case
 */
public record ForgotPasswordRequest(@NotBlank @Size(max = 320) String email) {

    @Override
    public String toString() {
        // The address is personal data: it stays out of any log line that prints the request.
        return "ForgotPasswordRequest[redacted]";
    }
}
