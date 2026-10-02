package app.platformapi;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Suspends, reinstates or deactivates an organization. The reason is free text typed by a platform administrator:
 * bounded, kept in the audit trail, never sent by mail and never shown to the organization.
 *
 * @param reason why, 1 to 200 characters; do not put personal data in it
 * @param confirm for a deactivation, the short name of the organization typed again; absent otherwise
 */
public record LifecycleRequest(@NotBlank @Size(max = 200) String reason, @Size(max = 63) String confirm) {

    @Override
    public String toString() {
        // The reason is free text typed by a platform person: it stays out of any log line that prints this record.
        return "LifecycleRequest[redacted]";
    }
}
