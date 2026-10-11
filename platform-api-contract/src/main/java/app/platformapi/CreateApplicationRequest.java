package app.platformapi;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Adds an application (Sprint 12): a named collection of navigation that members of some profiles see. The text is
 * typed by a person, so it never prints.
 *
 * @param name the name to build the API name from; the platform stores it with the ending {@code __c}
 * @param label the label people read
 * @param description what it is for, optional
 * @param definition the icon, the profiles and the navigation (schema {@code ApplicationDefinition})
 */
public record CreateApplicationRequest(@NotBlank @Size(max = 40) String name, @NotBlank @Size(max = 80) String label,
        @Size(max = 500) String description, @NotNull Map<String, Object> definition) {

    /** Copies the definition, so that it cannot be changed from outside. */
    public CreateApplicationRequest {
        definition = definition == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(definition));
    }

    @Override
    public String toString() {
        return "CreateApplicationRequest[redacted]";
    }
}
