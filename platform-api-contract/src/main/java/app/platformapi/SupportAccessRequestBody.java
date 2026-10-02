package app.platformapi;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * A platform person asks an organization for time-limited access.
 *
 * @param reason why, 1 to 200 characters; do not put personal data in it
 * @param minutes how long, 15 to 240 minutes (a longer time needs a new request)
 */
public record SupportAccessRequestBody(@NotBlank @Size(max = 200) String reason,
        @NotNull @Min(15) @Max(240) Integer minutes) {

    @Override
    public String toString() {
        // The reason is free text typed by a platform person: it stays out of any log line that prints this record.
        return "SupportAccessRequestBody[redacted]";
    }
}
