package app.platform.publicapi;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One node of the filter of a query (Sprint 30, ADR-0245): a group of conditions combined with AND or OR, or one
 * condition on one field. A closed model, never text: nothing in it is run as code or put into a statement.
 *
 * @param op the comparison, or AND/OR for a group
 * @param items for a group: the nodes combined, at least one
 * @param field for a condition: the API name of the field
 * @param values for a condition: none for IS_EMPTY and IS_NOT_EMPTY, two for BETWEEN, one or more for IN, one
 *        otherwise
 */
@Schema(description = "A group (`AND` or `OR` of `items`) or one condition on one field. A text is compared exactly: "
        + "upper and lower case differ. `NE` matches only records that have a value. `STARTS_WITH` and `CONTAINS` "
        + "are for text. The comparisons a field takes are listed in its description (`filterOperators`).")
public record PublicFilter(
        @NotNull @Schema(description = "The comparison, or AND / OR for a group.") PublicFilterOp op,
        @Schema(description = "For a group: the nodes combined, at least one.")
        List<@NotNull @Valid PublicFilter> items,
        @Size(max = 80) @Schema(description = "For a condition: the API name of the field. A field the caller may "
                + "not read is answered like a field that does not exist.") String field,
        @Size(max = 1000) @Schema(description = "For a condition: none for IS_EMPTY and IS_NOT_EMPTY, two for "
                + "BETWEEN, one or more for IN (at most 100), one otherwise. Text as text, a number as a number, a "
                + "checkbox as true or false, a day as `2026-10-31`, a day and time as `2026-10-31T09:30:00Z`, a "
                + "link as the identifier of the record.") List<Object> values) {

    /** Copies the lists. */
    public PublicFilter {
        items = items == null ? null : List.copyOf(items);
        values = values == null ? null : Collections.unmodifiableList(new ArrayList<>(values));
    }

    /** Prints the shape only: the values are what a person typed. */
    @Override
    public String toString() {
        return "PublicFilter[" + op + "]";
    }
}
