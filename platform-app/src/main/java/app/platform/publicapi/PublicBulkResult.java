package app.platform.publicapi;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * What happened to one record of a bulk call (Sprint 30, ADR-0245), at the position it had in the request.
 *
 * @param position the place of the record in the request, from 0
 * @param status what happened
 * @param id the record's identifier; absent for a failed create
 * @param version the version after the change; absent for a failure
 * @param error for a failure: why
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "The outcome of one record, at its position in the request.")
public record PublicBulkResult(
        @NotNull @Schema(description = "The place of the record in the request, from 0.", example = "0")
        Integer position,
        @NotNull @Schema(description = "What happened to the record.") PublicBulkStatus status,
        @Schema(description = "The identifier of the record. Absent for a failed create.") String id,
        @Schema(description = "The version of the record after the change. Absent for a failure.") Long version,
        @Schema(description = "Why the record was refused. Present only when `status` is FAILED.")
        PublicBulkError error) {
}
