package app.platform.publicapi;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * A record type the caller may use (Sprint 30, ADR-0245).
 *
 * @param apiName the permanent name, the value to send as {@code recordType}
 * @param label the label people read
 * @param isDefault whether a new record gets it when the caller chooses none
 */
@Schema(description = "A variant of an object that offers some of its fields and some of its picklist choices.")
public record PublicRecordType(
        @NotNull @Schema(description = "The API name: the value to send as `recordType`.") String apiName,
        @NotNull @Schema(description = "The label people read.") String label,
        @NotNull @Schema(description = "Whether a new record gets it when no record type is chosen.")
        Boolean isDefault) {
}
