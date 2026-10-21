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
 * @param indexed whether records filtered or sorted by this field are meant to be fast; left out means no. It serves
 *        queries only once its copy is complete (the index status endpoint of the object says when)
 * @param externalId whether the field holds the identifier the record has in another system (an import can upsert by
 *        it); left out means no. Such a field is also unique
 * @param searchable whether the object's search covers the field; left out means no
 * @param defaultValue the value a new record gets, or null
 * @param settings the settings of the type
 * @param retired whether the platform no longer offers the field for new use (it still exists)
 * @param editable whether the organization may change or remove it (only custom fields)
 * @param version the version to send back when changing it
 * @param problem for a formula that does not pass the expression language (it was saved before formulas were
 *        checked): what is wrong and where; it is not calculated and cannot be saved until fixed. Null otherwise
 * @param organizationValues whether the organization owns the list of values and the pairs of this platform-defined
 *        picklist and changes them with a field update (the rest of the field stays the platform's); left out means no
 * @param codeValues whether every value must be a short code (letters, digits, hyphen, underscore; at most 10), so it
 *        can be a part of a number; left out means no
 * @param platformWritten whether only the platform writes the field, nobody through the API, a bulk change or an
 *        import; left out means no
 */
public record FieldView(@NotNull String apiName, @NotNull String label, @NotNull String description,
        @NotNull String kind, @NotNull String type, @NotNull Boolean required, @NotNull Boolean unique,
        Boolean indexed, Boolean externalId, Boolean searchable, String defaultValue,
        @NotNull FieldSettings settings, @NotNull Boolean retired, @NotNull Boolean editable, @NotNull Long version,
        String problem, Boolean organizationValues, Boolean codeValues, Boolean platformWritten) {

    /** A field without the three flags of the Functional track. */
    public FieldView(String apiName, String label, String description, String kind, String type, Boolean required,
            Boolean unique, Boolean indexed, Boolean externalId, Boolean searchable, String defaultValue,
            FieldSettings settings, Boolean retired, Boolean editable, Long version, String problem) {
        this(apiName, label, description, kind, type, required, unique, indexed, externalId, searchable,
                defaultValue, settings, retired, editable, version, problem, null, null, null);
    }

    @Override
    public String toString() {
        return "FieldView[redacted]";
    }
}
