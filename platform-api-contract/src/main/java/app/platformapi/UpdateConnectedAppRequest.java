package app.platformapi;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Changes what a connected application may do (Sprint 31, ADR-0267). The member it acts as and its identifier cannot
 * be changed: a new person registers a new application. A change that narrows scopes or the object list ends the
 * tokens already issued, so the narrowing takes effect at once.
 *
 * @param name the name, 1 to 80 characters
 * @param description what it is for, up to 500 characters; may be absent
 * @param scopes the scopes it will hold, at least one, by key
 * @param objects the only objects it may use, or absent for every object its member may reach
 * @param tokenMinutes how long its tokens live
 * @param version the version read; the change is refused when the application has another version now
 */
public record UpdateConnectedAppRequest(
        @NotBlank @Size(max = 80) String name,
        @Size(max = 500) String description,
        @NotNull @Size(min = 1, max = 10) List<@NotBlank @Size(max = 40) String> scopes,
        @Size(max = 50) List<@Pattern(regexp = "^[A-Za-z][A-Za-z0-9_]{0,79}$") String> objects,
        @NotNull Integer tokenMinutes,
        long version) {
}
