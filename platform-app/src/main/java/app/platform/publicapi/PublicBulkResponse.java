package app.platform.publicapi;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * The outcome of a bulk call (Sprint 30, ADR-0245): one result for each record of the request, in its order.
 *
 * @param operation what the call did
 * @param allOrNone whether the call was all-or-none
 * @param committed whether anything was saved: false only for an all-or-none call that failed
 * @param succeeded how many records were written (0 when nothing was committed)
 * @param failed how many records were refused
 * @param results the outcome of each record
 */
@Schema(description = "The outcome of a bulk call.")
public record PublicBulkResponse(
        @NotNull @Schema(description = "What the call did.") PublicBulkOperation operation,
        @NotNull @Schema(description = "Whether the call was all-or-none.") Boolean allOrNone,
        @NotNull @Schema(description = "Whether anything was saved. False only for an all-or-none call that "
                + "failed.") Boolean committed,
        @NotNull @Schema(description = "How many records were written. 0 when nothing was committed.")
        Integer succeeded,
        @NotNull @Schema(description = "How many records were refused.") Integer failed,
        @NotNull @Schema(description = "The outcome of each record, in the order of the request.")
        List<PublicBulkResult> results) {

    /** Copies the list. */
    public PublicBulkResponse {
        results = List.copyOf(results);
    }
}
