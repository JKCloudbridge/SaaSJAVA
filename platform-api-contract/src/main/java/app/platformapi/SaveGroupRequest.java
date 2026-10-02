package app.platformapi;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Creates a public group or changes one (Sprint 8). The text is typed by a person, so it never prints.
 *
 * @param name the name, unique in the organization
 * @param description what the group is for, optional
 */
public record SaveGroupRequest(@NotBlank @Size(max = 80) String name, @Size(max = 500) String description) {

    @Override
    public String toString() {
        return "SaveGroupRequest[redacted]";
    }
}
