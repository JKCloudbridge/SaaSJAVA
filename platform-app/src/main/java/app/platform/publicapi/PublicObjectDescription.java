package app.platform.publicapi;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * One object with the fields the caller may read (Sprint 30, ADR-0245).
 *
 * @param apiName the permanent name
 * @param label the label for one record
 * @param pluralLabel the label for many
 * @param canCreate whether the caller may create records
 * @param canUpdate whether the caller may change records
 * @param canDelete whether the caller may delete records
 * @param recordTypes the record types the caller may use; empty when the object has none
 * @param fields the fields the caller may read, in the object's order
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "An object and the fields of it the caller may read. A field the caller may not read is not "
        + "listed, and writing it is answered like writing a field that does not exist.")
public record PublicObjectDescription(
        @NotNull @Schema(description = "The permanent API name.", example = "Employee__c") String apiName,
        @NotNull @Schema(description = "The label for one record.") String label,
        @NotNull @Schema(description = "The label for many records.") String pluralLabel,
        @NotNull @Schema(description = "Whether the caller may create records.") Boolean canCreate,
        @NotNull @Schema(description = "Whether the caller may change records.") Boolean canUpdate,
        @NotNull @Schema(description = "Whether the caller may delete records.") Boolean canDelete,
        @NotNull @Schema(description = "The record types the caller may use. Empty when the object has none.")
        List<PublicRecordType> recordTypes,
        @NotNull @Schema(description = "The fields the caller may read, in the object's order.")
        List<PublicField> fields) {

    /** Copies the lists. */
    public PublicObjectDescription {
        recordTypes = recordTypes == null ? List.of() : List.copyOf(recordTypes);
        fields = fields == null ? List.of() : List.copyOf(fields);
    }
}
