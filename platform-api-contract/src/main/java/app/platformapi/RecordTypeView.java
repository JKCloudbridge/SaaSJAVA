package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * One record type as the object manager shows it (Sprint 11).
 *
 * @param apiName the permanent name used by code and integrations, for example {@code Renewal__c}
 * @param label the label people read
 * @param description what the variant is for
 * @param active whether new records may use it
 * @param defaultType whether new records get it when nothing else is chosen
 * @param layout the page layout it uses, or null while layouts do not exist
 * @param allFields whether every field of the object is available
 * @param availableFields the fields available when {@code allFields} is false
 * @param picklistSubsets the picklist values this type allows (a picklist not listed allows all its values)
 * @param version the version to send back when changing it
 */
public record RecordTypeView(@NotNull String apiName, @NotNull String label, @NotNull String description,
        @NotNull Boolean active, @NotNull Boolean defaultType, String layout, @NotNull Boolean allFields,
        @NotNull List<String> availableFields, @NotNull List<PicklistSubset> picklistSubsets,
        @NotNull Long version) {

    /** Copies the lists. */
    public RecordTypeView {
        availableFields = availableFields == null ? List.of() : List.copyOf(availableFields);
        picklistSubsets = picklistSubsets == null ? List.of() : List.copyOf(picklistSubsets);
    }

    @Override
    public String toString() {
        return "RecordTypeView[redacted]";
    }
}
