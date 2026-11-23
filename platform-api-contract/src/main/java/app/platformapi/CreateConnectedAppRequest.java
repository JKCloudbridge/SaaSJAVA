package app.platformapi;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Registers a connected application (Sprint 31, ADR-0267). It acts as the person who registers it; there is no field
 * to choose another member, on purpose.
 *
 * @param name a name for people, 1 to 80 characters
 * @param description what it is for, up to 500 characters; may be absent
 * @param scopes the scopes it will hold, at least one, by key (the list is {@code GET /connected-apps/catalogue})
 * @param objects the API names of the only objects it may use, or absent for every object its member may reach
 * @param tokenMinutes how long its tokens live, within the limits of the catalogue; absent for the default
 */
public record CreateConnectedAppRequest(
        @NotBlank @Size(max = 80) String name,
        @Size(max = 500) String description,
        @NotNull @Size(min = 1, max = 10) List<@NotBlank @Size(max = 40) String> scopes,
        @Size(max = 50) List<@Pattern(regexp = "^[A-Za-z][A-Za-z0-9_]{0,79}$") String> objects,
        Integer tokenMinutes) {
}
