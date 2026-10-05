package app.platformapi;

import jakarta.validation.constraints.NotNull;

/**
 * One field of an object as the object manager shows it (Sprint 10).
 *
 * @param apiName the permanent name used by code and integrations, for example {@code accountId} or {@code salary__c}
 * @param label the label people read
 * @param description what the field is for
 * @param kind {@code SYSTEM} (every object has it), {@code STANDARD} (defined by the platform for this object) or
 *        {@code CUSTOM} (made by the organization)
 * @param type the field type, for example {@code TEXT}
 * @param required whether a record must have a value
 * @param unique whether no two records may have the same value
 * @param defaultValue the value a new record gets, or null
 * @param settings the settings of the type
 * @param retired whether the platform no longer offers the field for new use (it still exists)
 * @param editable whether the organization may change or remove it (only custom fields)
 * @param version the version to send back when changing it
 */
public record FieldView(@NotNull String apiName, @NotNull String label, @NotNull String description,
        @NotNull String kind, @NotNull String type, @NotNull Boolean required, @NotNull Boolean unique,
        String defaultValue,
        @NotNull FieldSettings settings, @NotNull Boolean retired, @NotNull Boolean editable, @NotNull Long version) {

    @Override
    public String toString() {
        return "FieldView[redacted]";
    }
}
