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
 */
public record ObjectDefinition(String apiName, String label, String pluralLabel, String description,
        DefinitionKind kind, String managedBy, boolean extensible, long version, List<FieldDefinition> fields) {

    /** Copies what is given. */
    public ObjectDefinition {
        fields = List.copyOf(fields);
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
