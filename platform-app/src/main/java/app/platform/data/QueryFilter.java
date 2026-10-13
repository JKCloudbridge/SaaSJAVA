package app.platform.data;

import java.util.List;

/**
 * One node of the filter of a {@link RecordQuery}: a group of conditions combined with AND or OR, or one condition on
 * one field (Sprint 16, ADR-0100). A closed model, never text: a field is named by its API name and checked against the
 * metadata, a value is data, and no operator takes an expression, so nothing a person sends is ever run as code or
 * spliced into a statement.
 *
 * @param op what the node does
 * @param items for AND and OR: the nodes combined, at least one; empty otherwise
 * @param field for a condition: the API name of the field; null for a group
 * @param values for a condition: the values compared with (none for the emptiness tests, two for BETWEEN, one or more
 *        for IN, one otherwise); empty for a group. The values are of the shapes a record holds: text, a number (as a
 *        {@code Number} or text), true or false, and days, times and identifiers as text
 */
public record QueryFilter(Op op, List<QueryFilter> items, String field, List<Object> values) {

    /** What a node does. A text is compared exactly: upper and lower case differ (ADR-0095). */
    public enum Op {
        /** All the items. */
        AND,
        /** Any of the items. */
        OR,
        /** The field has this value. */
        EQ,
        /** The field has a value and it is another one (a record without a value does not match). */
        NE,
        /** Greater than. */
        GT,
        /** Greater than or equal. */
        GTE,
        /** Less than. */
        LT,
        /** Less than or equal. */
        LTE,
        /** From the first value to the second, both included. */
        BETWEEN,
        /** One of the values. */
        IN,
        /** The field has no value. */
        IS_EMPTY,
        /** The field has a value. */
        IS_NOT_EMPTY,
        /** A text that starts with the value. Reads every record unless another condition narrows them. */
        STARTS_WITH,
        /** A text that contains the value. Reads every record unless another condition narrows them. */
        CONTAINS;

        /** Whether the node is a group. */
        public boolean isGroup() {
            return this == AND || this == OR;
        }
    }

    /** Copies the lists. */
    public QueryFilter {
        items = items == null ? List.of() : List.copyOf(items);
        values = values == null ? List.of() : List.copyOf(values);
    }

    /** All of the nodes. */
    public static QueryFilter and(List<QueryFilter> items) {
        return new QueryFilter(Op.AND, items, null, List.of());
    }

    /** Any of the nodes. */
    public static QueryFilter or(List<QueryFilter> items) {
        return new QueryFilter(Op.OR, items, null, List.of());
    }

    /** A condition on a field. */
    public static QueryFilter on(String field, Op op, Object... values) {
        return new QueryFilter(op, List.of(), field, List.of(values));
    }

    /** Prints the shape only: the values are what a person typed. */
    @Override
    public String toString() {
        return "QueryFilter[" + op + (op != null && op.isGroup() ? " x" + items.size() : "") + "]";
    }
}
