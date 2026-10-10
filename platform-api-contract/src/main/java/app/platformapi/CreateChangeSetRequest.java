package app.platformapi;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Starts a change set, a named group of intended changes that is published all together (Sprint 11). The text is typed
 * by a person, so it never prints.
 *
 * @param name the name people read, unique among the organization's open change sets
 * @param description what the group is for, optional
 */
public record CreateChangeSetRequest(@NotBlank @Size(max = 80) String name, @Size(max = 500) String description) {

    @Override
    public String toString() {
        return "CreateChangeSetRequest[redacted]";
    }
}
