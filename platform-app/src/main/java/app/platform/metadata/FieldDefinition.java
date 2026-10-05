package app.platform.metadata;

/**
 * One field of an object (ADR-0058, ADR-0060): its identity (the API name, permanent), what people read (label,
 * description), its type with the settings of that type, and its constraints.
 *
 * @param objectApiName the API name of the object it belongs to
 * @param apiName the permanent name used by code and integrations ({@code accountId}, {@code salary__c})
 * @param label the label people read
 * @param description what the field is for
 * @param kind who defined it
 * @param type the kind of value it holds
 * @param required whether a record must have a value
 * @param unique whether no two records may have the same value
 * @param defaultValue the value a new record gets, written as text, or null
 * @param configuration the settings of the type
 * @param retired whether the platform no longer offers it for new use (it still exists)
 * @param version the version, for concurrent changes (0 for fields the platform defines)
 */
public record FieldDefinition(String objectApiName, String apiName, String label, String description,
        DefinitionKind kind, FieldType type, boolean required, boolean unique, String defaultValue,
        FieldConfiguration configuration, boolean retired, long version) {

    /** The same field on another object: the system fields are defined once and every object has them. */
    public FieldDefinition forObject(String otherObjectApiName) {
        return new FieldDefinition(otherObjectApiName, apiName, label, description, kind, type, required, unique,
                defaultValue, configuration, retired, version);
    }

    /** The key of permissions on this field: the object, a dot and the field. */
    public String permissionKey() {
        return objectApiName + "." + apiName;
    }

    /** Prints the identity only: labels and descriptions are text a person chose (ADR-0012). */
    @Override
    public String toString() {
        return "FieldDefinition[" + objectApiName + "." + apiName + ", " + type + "]";
    }
}
