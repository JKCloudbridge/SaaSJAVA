package app.platformapi;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Sets the size of one licence pool of an organization.
 *
 * @param quantity how many licences; not below what is in use
 * @param reason why, 1 to 200 characters, kept in the audit trail
 */
public record SetPoolRequest(@NotNull @Min(0) @Max(100000) Integer quantity,
        @NotBlank @Size(max = 200) String reason) {

    @Override
    public String toString() {
        // The reason is free text typed by a platform person: it stays out of any log line that prints this record.
        return "SetPoolRequest[redacted]";
    }
}
