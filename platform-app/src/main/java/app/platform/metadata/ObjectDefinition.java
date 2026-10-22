package app.platform.metadata;

import java.util.List;
import java.util.Optional;

/**
 * One object (a kind of record) with its fields (ADR-0058). Standard and custom objects are the same shape, so
 * everything that consumes metadata works on both.
 *
 * @param apiName the permanent name used by code and integrations ({@code Account}, {@code Employee__c})
 * @param label the label people read
 * @param pluralLabel the label for many
 * @param description what the object is for
 * @param kind {@link DefinitionKind#STANDARD} or {@link DefinitionKind#CUSTOM}
 * @param managedBy the part of the platform that owns the records of a standard object (for example {@code identity}),
 *        or null when the records are ordinary organization data. A record of a managed object is changed only through
 *        its owner, never by the generic record store (Sprint 14).
 * @param extensible whether the organization may add its own fields
 * @param version the version, for concurrent changes (0 for objects the platform defines)
 * @param fields the fields: system fields first, then standard, then custom, each group in a stable order
 * @param requiresFeature the plan feature an organization needs for the object to exist for it, or null (Functional
 *        track FA1, ADR-0175). Asked when the object is looked up, never kept in the snapshot, so an organization
 *        without the feature gets "not found" exactly as for an unknown object.
 * @param retainRecords whether every record of the object is kept: it cannot be deleted, restored over or purged,
 *        by anyone, and no delete right can be given on it (ADR-0174 section 7, ADR-0181)
 * @param sharingDefault the organization-wide default the platform fixes for the records of the object, or null when
 *        an administrator chooses it. Only {@code PRIVATE} exists: a record is then reached only by its owner, a
 *        member who may view every record, and whom it is shared with, the role hierarchy adds nothing and no
 *        administrator can change it (FA4, ADR-0189)
 */
public record ObjectDefinition(String apiName, String label, String pluralLabel, String description,
        DefinitionKind kind, String managedBy, boolean extensible, long version, List<FieldDefinition> fields,
        String requiresFeature, boolean retainRecords, String sharingDefault) {

    /** Copies what is given. */
    public ObjectDefinition {
        fields = List.copyOf(fields);
    }

    /** An object whose sharing default an administrator chooses (every object before Sprint FA4). */
    public ObjectDefinition(String apiName, String label, String pluralLabel, String description,
            DefinitionKind kind, String managedBy, boolean extensible, long version, List<FieldDefinition> fields,
            String requiresFeature, boolean retainRecords) {
        this(apiName, label, pluralLabel, description, kind, managedBy, extensible, version, fields, requiresFeature,
                retainRecords, null);
    }

    /** An object that needs no plan feature and whose records may be deleted (every object before Sprint FA1). */
    public ObjectDefinition(String apiName, String label, String pluralLabel, String description,
            DefinitionKind kind, String managedBy, boolean extensible, long version, List<FieldDefinition> fields) {
        this(apiName, label, pluralLabel, description, kind, managedBy, extensible, version, fields, null, false,
                null);
    }

    /** The same object with other fields (the organization's own fields added to a standard object). */
    public ObjectDefinition withFields(List<FieldDefinition> other) {
        return new ObjectDefinition(apiName, label, pluralLabel, description, kind, managedBy, extensible, version,
                other, requiresFeature, retainRecords, sharingDefault);
    }

    /** The field with the API name, if the object has it. */
    public Optional<FieldDefinition> field(String fieldApiName) {
        return fields.stream().filter(field -> field.apiName().equals(fieldApiName)).findFirst();
    }

    /** Whether an organization made the object. */
    public boolean isCustom() {
        return kind == DefinitionKind.CUSTOM;
    }

    /** Prints the identity only: labels and descriptions are text a person chose (ADR-0012). */
    @Override
    public String toString() {
        return "ObjectDefinition[" + apiName + "]";
    }
}
