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
 * @param indexed whether records filtered or sorted by this field are meant to be fast: an intent of the
 *        organization (or of the platform's own definition file); the slot that serves it, and whether it is complete
 *        yet, is state of the data module (Sprint 16, ADR-0101)
 * @param externalId whether the field holds the identifier the record has in another system, so that an import can
 *        find the record by it (Sprint 17, ADR-0123); such a field is unique
 * @param searchable whether the search of the object covers the field (Sprint 17, ADR-0124)
 * @param organizationValues whether the organization owns the list of values (and the pairs of a dependent picklist)
 *        of this platform-defined picklist: the platform ships a starting list, the organization's administrator
 *        changes it (FA2A, ADR-0182)
 * @param codeValues whether every value of the picklist is a short code ({@code [A-Za-z0-9_-]{1,10}}), so that it can
 *        be a part of a number (FA2A, ADR-0182, ADR-0184)
 * @param platformWritten whether only the platform writes the field: no member, not even one with every right, can
 *        set it through the API, a bulk change or an import (FA2A, ADR-0183)
 */
public record FieldDefinition(String objectApiName, String apiName, String label, String description,
        DefinitionKind kind, FieldType type, boolean required, boolean unique, String defaultValue,
        FieldConfiguration configuration, boolean retired, long version, boolean indexed, boolean externalId,
        boolean searchable, boolean organizationValues, boolean codeValues, boolean platformWritten) {

    /** A field that is not flagged as indexed, as an external identifier or as searchable. */
    public FieldDefinition(String objectApiName, String apiName, String label, String description,
            DefinitionKind kind, FieldType type, boolean required, boolean unique, String defaultValue,
            FieldConfiguration configuration, boolean retired, long version) {
        this(objectApiName, apiName, label, description, kind, type, required, unique, defaultValue, configuration,
                retired, version, false, false, false);
    }

    /** A field without the three flags of the Functional track (every field before FA2A). */
    public FieldDefinition(String objectApiName, String apiName, String label, String description,
            DefinitionKind kind, FieldType type, boolean required, boolean unique, String defaultValue,
            FieldConfiguration configuration, boolean retired, long version, boolean indexed, boolean externalId,
            boolean searchable) {
        this(objectApiName, apiName, label, description, kind, type, required, unique, defaultValue, configuration,
                retired, version, indexed, externalId, searchable, false, false, false);
    }

    /** A field with its index flag and without the other two flags. */
    public FieldDefinition(String objectApiName, String apiName, String label, String description,
            DefinitionKind kind, FieldType type, boolean required, boolean unique, String defaultValue,
            FieldConfiguration configuration, boolean retired, long version, boolean indexed) {
        this(objectApiName, apiName, label, description, kind, type, required, unique, defaultValue, configuration,
                retired, version, indexed, false, false);
    }

    /** The same field on another object: the system fields are defined once and every object has them. */
    public FieldDefinition forObject(String otherObjectApiName) {
        return new FieldDefinition(otherObjectApiName, apiName, label, description, kind, type, required, unique,
                defaultValue, configuration, retired, version, indexed, externalId, searchable, organizationValues,
                codeValues, platformWritten);
    }

    /**
     * The same field with the settings and the version of the organization's own list of values (FA2A, ADR-0182). The
     * version is what a caller must send back to change the list; it is never 0 once the organization has changed it.
     */
    public FieldDefinition withValues(FieldConfiguration other, long otherVersion) {
        return new FieldDefinition(objectApiName, apiName, label, description, kind, type, required, unique,
                defaultValue, other, retired, otherVersion, indexed, externalId, searchable, organizationValues,
                codeValues, platformWritten);
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
