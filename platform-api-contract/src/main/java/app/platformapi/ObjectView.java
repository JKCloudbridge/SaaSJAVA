package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * One object with all its fields as the object manager shows it (Sprint 10).
 *
 * @param apiName the permanent name used by code and integrations, for example {@code Account} or {@code Employee__c}
 * @param label the label people read
 * @param pluralLabel the label for many
 * @param description what the object is for
 * @param kind {@code STANDARD} (defined by the platform, protected) or {@code CUSTOM} (made by the organization)
 * @param managedBy the part of the platform that owns the records of a standard object, or null when the records are
 *        ordinary organization data
 * @param editable whether the organization may change or remove the object itself (only custom objects)
 * @param extensible whether the organization may add its own fields to it
 * @param version the version to send back when changing a custom object (0 for a standard one)
 * @param fields the fields: system fields first, then the standard ones, then the ones of the organization
 */
public record ObjectView(@NotNull String apiName, @NotNull String label, @NotNull String pluralLabel,
        @NotNull String description, @NotNull String kind, String managedBy, @NotNull Boolean editable,
        @NotNull Boolean extensible,
        @NotNull Long version, @NotNull List<FieldView> fields) {

    public ObjectView {
        fields = fields == null ? List.of() : List.copyOf(fields);
    }

    @Override
    public String toString() {
        return "ObjectView[redacted]";
    }
}
