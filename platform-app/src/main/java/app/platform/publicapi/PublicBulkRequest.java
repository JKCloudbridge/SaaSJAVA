package app.platform.publicapi;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Creates, changes or deletes many records of one object in one call (Sprint 30, ADR-0245).
 *
 * @param operation what the call does to every record
 * @param allOrNone when true, one record that cannot be written undoes the whole call
 * @param records the records, at most 200, in the order of the answer
 */
@Schema(description = "Every record goes through exactly the steps the same single request goes through, and is "
        + "audited and announced like it. By default each record stands on its own: one that is refused is undone "
        + "alone and the others are saved. With `allOrNone` one refusal undoes the whole call. At most 200 records "
        + "and 8 MiB per call.")
public record PublicBulkRequest(
        @NotNull @Schema(description = "What the call does to every record.") PublicBulkOperation operation,
        @Schema(description = "When true, one refused record undoes the whole call (`committed` is false and "
                + "nothing is saved). Left out: false.") Boolean allOrNone,
        @NotEmpty @Size(max = 200) @Schema(description = "The records, at most 200. The answer has one result for "
                + "each, in this order.") List<@NotNull @Valid PublicBulkItem> records) {

    /** Copies the list. */
    public PublicBulkRequest {
        records = records == null ? List.of() : List.copyOf(records);
    }

    /** Prints nothing of the records. */
    @Override
    public String toString() {
        return "PublicBulkRequest[redacted]";
    }
}
