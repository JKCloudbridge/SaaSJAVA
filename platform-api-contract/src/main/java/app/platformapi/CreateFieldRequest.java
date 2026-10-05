package app.platformapi;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Adds a custom field to an object (Sprint 10). The text is typed by a person, so it never prints.
 *
 * @param name the name to build the API name from, for example {@code salary}: letters, digits and single
 *        underscores, starting with a lower case letter. The platform stores it as {@code salary__c}.
 * @param label the label people read
 * @param description what the field is for, optional
 * @param type the field type (the field types endpoint lists them); it can never be changed afterwards
 * @param required whether a record must have a value (not for every type)
 * @param unique whether no two records may have the same value (not for every type)
 * @param defaultValue the value a new record gets, written as text, optional (not for every type)
 * @param settings the settings of the type, optional
 */
public record CreateFieldRequest(@NotBlank @Size(max = 40) String name, @NotBlank @Size(max = 80) String label,
        @Size(max = 500) String description, @NotBlank @Size(max = 20) String type, Boolean required, Boolean unique,
        @Size(max = 4000) String defaultValue, @Valid FieldSettings settings) {

    @Override
    public String toString() {
        return "CreateFieldRequest[redacted]";
    }
}
