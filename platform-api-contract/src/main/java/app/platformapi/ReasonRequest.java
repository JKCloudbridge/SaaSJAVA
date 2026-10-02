package app.platformapi;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * A platform action that needs a reason in the audit trail (sign-out of an organization, of a person, a resend).
 *
 * @param reason why, 1 to 200 characters; do not put personal data in it
 */
public record ReasonRequest(@NotBlank @Size(max = 200) String reason) {

    @Override
    public String toString() {
        // The reason is free text typed by a platform person: it stays out of any log line that prints this record.
        return "ReasonRequest[redacted]";
    }
}
