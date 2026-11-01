package app.platform.publicapi;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;

/**
 * Why one record of a bulk call was refused (Sprint 30, ADR-0245): the error a single request would have answered.
 *
 * @param code the stable error code; branch on this, never on the message
 * @param message the safe message
 * @param fields for a validation error: the problems by place
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "The error a single request for this record would have answered.")
public record PublicBulkError(
        @NotNull @Schema(description = "The stable error code, one of the values of `ErrorCode`.",
                example = "VALIDATION_ERROR") String code,
        @NotNull @Schema(description = "A safe message in English.") String message,
        @Schema(description = "For a validation error: the problems, by the place that is wrong.")
        Map<String, List<String>> fields) {

    /** Copies the problems. */
    public PublicBulkError {
        fields = fields == null || fields.isEmpty() ? null : Map.copyOf(fields);
    }
}
