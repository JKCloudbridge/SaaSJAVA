package app.platformapi;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Names a person by address, in the body so the address never appears in a URL or an access log.
 *
 * @param email the address
 * @param reason why, 1 to 200 characters, kept in the audit trail
 */
public record SessionLookupRequest(@NotBlank @Size(max = 254) String email,
        @NotBlank @Size(max = 200) String reason) {

    @Override
    public String toString() {
        return "SessionLookupRequest[redacted]";
    }
}
