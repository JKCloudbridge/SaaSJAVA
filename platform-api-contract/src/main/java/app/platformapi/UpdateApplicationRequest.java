package app.platformapi;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Changes an application (Sprint 12). The API name never changes. The text is typed by a person, so it never prints.
 *
 * @param label the label people read
 * @param description what it is for, optional
 * @param definition the whole definition again (schema {@code ApplicationDefinition})
 * @param version the version the caller last read; a change on top of a newer one is refused
 */
public record UpdateApplicationRequest(@NotBlank @Size(max = 80) String label, @Size(max = 500) String description,
        @NotNull Map<String, Object> definition, @NotNull Long version) {

    /** Copies the definition, so that it cannot be changed from outside. */
    public UpdateApplicationRequest {
        definition = definition == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(definition));
    }

    @Override
    public String toString() {
        return "UpdateApplicationRequest[redacted]";
    }
}
