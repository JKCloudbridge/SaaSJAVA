package app.platform.publicapi;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * One key of the order of a query (Sprint 30, ADR-0245).
 *
 * @param field the API name of a field the caller may read whose description says it is sortable
 * @param descending whether the largest comes first; left out means smallest first
 */
@Schema(description = "One key of the order of a query. Records without a value come last in both directions.")
public record PublicSortKey(
        @NotBlank @Size(max = 80) @Schema(description = "The API name of the field. Only a field whose description "
                + "says `sortable` can be used.") String field,
        @Schema(description = "Whether the largest comes first. Left out: smallest first.") Boolean descending) {
}
