package app.platformapi;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The start of a sign-up: only the address. Nothing else is asked until the person has proved the address is theirs
 * by opening the link that is mailed to it (ADR-0023).
 *
 * @param email the address, any case
 */
public record SignUpRequest(@NotBlank @Size(max = 320) String email) {

    @Override
    public String toString() {
        // The address is personal data: it stays out of any log line that prints the request.
        return "SignUpRequest[redacted]";
    }
}
