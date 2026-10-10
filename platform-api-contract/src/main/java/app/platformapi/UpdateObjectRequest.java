package app.platformapi;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Changes the labels and the description of a custom object (Sprint 10). The API name never changes.
 *
 * @param label the label people read
 * @param pluralLabel the label for many
 * @param description what the object is for, optional
 * @param version the version the caller last read; a change on top of a newer one is refused (CONFLICT)
 */
public record UpdateObjectRequest(@NotBlank @Size(max = 80) String label, @NotBlank @Size(max = 80) String pluralLabel,
        @Size(max = 500) String description, @NotNull Long version) {

    @Override
    public String toString() {
        return "UpdateObjectRequest[redacted]";
    }
}
