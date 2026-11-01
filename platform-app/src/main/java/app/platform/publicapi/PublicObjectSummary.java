package app.platform.publicapi;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * One object the caller may read, in the list of objects (Sprint 30, ADR-0245).
 *
 * @param apiName the permanent name used in the address of every operation
 * @param label the label for one record
 * @param pluralLabel the label for many
 * @param kind {@code STANDARD} (defined by the platform) or {@code CUSTOM} (defined by the organization)
 * @param canCreate whether the caller may create records of the object
 * @param canUpdate whether the caller may change records of the object
 * @param canDelete whether the caller may delete records of the object
 */
@Schema(description = "An object whose records the caller may read. Standard and custom objects are used in exactly "
        + "the same way.")
public record PublicObjectSummary(
        @NotNull @Schema(description = "The permanent API name, used in the address of every operation.",
                example = "Employee__c") String apiName,
        @NotNull @Schema(description = "The label for one record.", example = "Employee") String label,
        @NotNull @Schema(description = "The label for many records.", example = "Employees") String pluralLabel,
        @NotNull @Schema(description = "STANDARD or CUSTOM.", allowableValues = {"STANDARD", "CUSTOM"}) String kind,
        @NotNull @Schema(description = "Whether the caller may create records. Presentation of the caller's rights: "
                + "every request is decided again.") Boolean canCreate,
        @NotNull @Schema(description = "Whether the caller may change records.") Boolean canUpdate,
        @NotNull @Schema(description = "Whether the caller may delete records.") Boolean canDelete) {
}
