package app.platform.publicapi;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * A question put to the records of an object (Sprint 30, ADR-0245): which ones, in which order, which fields, how
 * many. The closed query model of the platform: nothing in it is run as code or put into a statement.
 *
 * @param filter the conditions; left out means every record the caller may reach
 * @param sort at most two keys; left out means identifier order
 * @param fields the fields to bring, each one the caller may read; left out means every field the caller may read
 * @param resolve links to follow, one level
 * @param limit the page size, 1 to 200; left out means 50
 * @param cursor the cursor of the previous page of this same query; left out means the first page
 */
@Schema(description = "A query. There is no total in the answer: a page says whether another follows and gives a "
        + "cursor that is good only for the same query. A filter or an order on a field without a complete index is "
        + "allowed only on an object with few records; on a larger one the answer is a validation error that says "
        + "which field to flag as indexed. Sorting by `updatedAt` is not offered.")
public record PublicQueryRequest(
        @Valid PublicFilter filter,
        @Size(max = 2) @Schema(description = "At most two keys. Records without a value come last in both "
                + "directions; ties are broken by the identifier.") List<@NotNull @Valid PublicSortKey> sort,
        @Size(max = 500) @Schema(description = "The fields to bring (API names). Left out: every field the caller "
                + "may read.") List<@NotBlank @Size(max = 80) String> fields,
        @Size(max = 500) @Schema(description = "Links to follow, one level.")
        List<@NotNull @Valid PublicResolve> resolve,
        @Min(1) @Max(200) @Schema(description = "The page size, 1 to 200. Left out: 50.", example = "50")
        Integer limit,
        @Size(max = 4096) @Schema(description = "The `nextCursor` of the previous page of this same query.")
        String cursor) {

    /** Copies the lists. */
    public PublicQueryRequest {
        sort = sort == null ? null : List.copyOf(sort);
        fields = fields == null ? null : List.copyOf(fields);
        resolve = resolve == null ? null : List.copyOf(resolve);
    }

    /** Prints nothing of the question. */
    @Override
    public String toString() {
        return "PublicQueryRequest[redacted]";
    }
}
