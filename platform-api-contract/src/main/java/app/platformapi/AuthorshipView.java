package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/**
 * Who made a definition of an object and who last changed it (UA3, ADR-0169).
 *
 * <p>A name is the display name of a person who is an active member of the organization, or {@code Platform} for a
 * definition the platform itself made. It is absent for a person who is no longer a member; the answer never says why,
 * and never carries an identifier or an address.
 *
 * @param kind {@code OBJECT}, {@code FIELD}, {@code RECORD_TYPE}, {@code LAYOUT}, {@code RECORD_PAGE} or
 *         {@code VALIDATION_RULE}
 * @param apiName the permanent name of the definition (of the object itself for kind {@code OBJECT})
 * @param createdBy who made it, when that can be shown
 * @param createdAt when it was made
 * @param updatedBy who last changed it, when that can be shown
 * @param updatedAt when it was last changed
 */
public record AuthorshipView(@NotNull String kind, @NotNull String apiName, String createdBy,
        @NotNull Instant createdAt, String updatedBy, @NotNull Instant updatedAt) {
}
