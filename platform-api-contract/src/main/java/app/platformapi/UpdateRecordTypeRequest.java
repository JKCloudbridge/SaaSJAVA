package app.platformapi;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Changes a record type (Sprint 11). The API name and the object never change. The text is typed by a person, so it
 * never prints.
 *
 * @param label the label people read
 * @param description what the variant is for, optional
 * @param active whether new records may use it; left out means yes
 * @param defaultType whether new records get it when nothing else is chosen; left out means no
 * @param availableFields the API names of the fields a record of this type offers; left out or empty means every field
 * @param picklistSubsets for picklist fields, the values this type allows
 * @param version the version the caller last read; a change on top of a newer one is refused (CONFLICT)
 */
public record UpdateRecordTypeRequest(@NotBlank @Size(max = 80) String label, @Size(max = 500) String description,
        Boolean active, Boolean defaultType, @Size(max = 500) List<@NotBlank @Size(max = 60) String> availableFields,
        @Size(max = 500) List<@Valid PicklistSubset> picklistSubsets, @NotNull Long version) {

    /** Copies the lists (a list that was not given stays absent). */
    public UpdateRecordTypeRequest {
        availableFields = availableFields == null ? null : List.copyOf(availableFields);
        picklistSubsets = picklistSubsets == null ? null : List.copyOf(picklistSubsets);
    }

    @Override
    public String toString() {
        return "UpdateRecordTypeRequest[redacted]";
    }
}
