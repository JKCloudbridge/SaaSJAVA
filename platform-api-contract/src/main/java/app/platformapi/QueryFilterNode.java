package app.platformapi;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One node of the filter of a record query (Sprint 16): a group of conditions combined with {@code AND} or
 * {@code OR}, or one condition on one field. The values are what a person typed, so the node never prints.
 *
 * @param op {@code AND}, {@code OR} (a group) or one of {@code EQ}, {@code NE}, {@code GT}, {@code GTE},
 *        {@code LT}, {@code LTE}, {@code BETWEEN}, {@code IN}, {@code IS_EMPTY}, {@code IS_NOT_EMPTY},
 *        {@code STARTS_WITH}, {@code CONTAINS} (a condition). A text is compared exactly: upper and lower case differ.
 *        {@code NE} matches only records that have a value; {@code STARTS_WITH} and {@code CONTAINS} are for text
 * @param items for a group: the nodes combined, at least one
 * @param field for a condition: the API name of the field. A field the caller may not read is answered like a field
 *        that does not exist
 * @param values for a condition: none for {@code IS_EMPTY} and {@code IS_NOT_EMPTY}, two for {@code BETWEEN}, one or
 *        more (at most the limit of the organization) for {@code IN}, one otherwise. Text as text, a number as a number
 *        or text, a checkbox as true or false, a day as {@code 2026-10-12}, a day and time as
 *        {@code 2026-10-12T09:30:00Z}, a link as the identifier of the record
 */
public record QueryFilterNode(@NotNull @Size(max = 20) String op,
        List<@NotNull @Valid QueryFilterNode> items, @Size(max = 80) String field,
        @Size(max = 1000) List<Object> values) {

    /** Copies the lists. */
    public QueryFilterNode {
        items = items == null ? null : List.copyOf(items);
        values = values == null ? null : Collections.unmodifiableList(new ArrayList<>(values));
    }

    @Override
    public String toString() {
        return "QueryFilterNode[redacted]";
    }
}
