package app.platformapi;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Adds a record type to an object (Sprint 11). The text is typed by a person, so it never prints.
 *
 * @param name the name to build the API name from, for example {@code Renewal}; the platform stores it as
 *        {@code Renewal__c}
 * @param label the label people read
 * @param description what the variant is for, optional
 * @param active whether new records may use it; left out means yes
 * @param defaultType whether new records get it when nothing else is chosen; left out means no
 * @param availableFields the API names of the fields a record of this type offers; left out or empty means every field
 * @param picklistSubsets for picklist fields, the values this type allows; a picklist not named allows all its values
 */
public record CreateRecordTypeRequest(@NotBlank @Size(max = 40) String name, @NotBlank @Size(max = 80) String label,
        @Size(max = 500) String description, Boolean active, Boolean defaultType,
        @Size(max = 500) List<@NotBlank @Size(max = 60) String> availableFields,
        @Size(max = 500) List<@Valid PicklistSubset> picklistSubsets) {

    /** Copies the lists (a list that was not given stays absent). */
    public CreateRecordTypeRequest {
        availableFields = availableFields == null ? null : List.copyOf(availableFields);
        picklistSubsets = picklistSubsets == null ? null : List.copyOf(picklistSubsets);
    }

    @Override
    public String toString() {
        return "CreateRecordTypeRequest[redacted]";
    }
}
