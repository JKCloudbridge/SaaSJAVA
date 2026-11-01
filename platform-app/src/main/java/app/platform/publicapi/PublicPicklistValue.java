package app.platform.publicapi;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * One value of a picklist field (Sprint 30, ADR-0245).
 *
 * @param value the stored value, the one to send
 * @param label the label people read
 * @param active whether the value can be chosen for new records
 */
@Schema(description = "One choice of a picklist.")
public record PublicPicklistValue(
        @NotNull @Schema(description = "The value to send.") String value,
        @NotNull @Schema(description = "The label people read.") String label,
        @NotNull @Schema(description = "Whether the value can be chosen for new records.") Boolean active) {
}
