package app.platformapi;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Changes a custom field (Sprint 10). The API name, the object and the type never change. The text is typed by a
 * person, so it never prints.
 *
 * @param label the label people read
 * @param description what the field is for, optional
 * @param required whether a record must have a value; left out means no
 * @param unique whether no two records may have the same value; left out means no
 * @param defaultValue the value a new record gets, written as text, optional
 * @param settings the settings of the type; a picklist must still list every value it had (switch one off instead)
 * @param version the version the caller last read; a change on top of a newer one is refused (CONFLICT)
 */
public record UpdateFieldRequest(@NotBlank @Size(max = 80) String label, @Size(max = 500) String description,
        Boolean required, Boolean unique, @Size(max = 4000) String defaultValue, @Valid FieldSettings settings,
        @NotNull Long version) {

    @Override
    public String toString() {
        return "UpdateFieldRequest[redacted]";
    }
}
