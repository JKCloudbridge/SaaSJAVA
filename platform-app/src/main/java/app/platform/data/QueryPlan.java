package app.platform.data;

import java.util.List;
import java.util.UUID;

/**
 * A query after it was checked and planned: what the storage turns into a parameterized statement (Sprint 16,
 * ADR-0100). Every name in it comes from the metadata catalogue or from this platform (a slot column, a system
 * column); every value is text that the storage passes as a parameter and converts in the database. Nothing in it is
 * SQL, and the storage never builds SQL from a value.
 *
 * <p>The plan has already decided <em>how</em> to read each field: from its slot when the copy is complete, from the
 * record's values otherwise, or from a column of the record (the system fields). It has also added what record-level
 * security demands (Sprint 17 puts its predicates in {@code where}).
 *
 * @param objectApiName the object
 * @param where the conditions every returned record meets, or null for none
 * @param order the keys of the order (empty for identifier order); records without a value come last
 * @param after where the previous page ended, or null for the first page
 * @param limit how many records to return (the storage may be asked for one more to learn whether there are more)
 */
public record QueryPlan(String objectApiName, Node where, List<SortKey> order, After after, int limit) {

    /** How a condition compares. */
    public enum Compare { EQ, NE, GT, GTE, LT, LTE, BETWEEN, IN, IS_EMPTY, IS_NOT_EMPTY, STARTS_WITH, CONTAINS }

    /** The kind of value a source holds and a condition compares. */
    public enum ValueKind { TEXT, NUMBER, DATE, TIMESTAMP, UUID, BOOLEAN }

    /** The columns of the record itself that a field of the system can be read from. */
    public enum SystemColumn { ID, OWNER, CREATED_AT, CREATED_BY, UPDATED_AT, UPDATED_BY }

    /** A condition or a group of conditions. */
    public sealed interface Node permits Group, Test {
    }

    /**
     * Conditions combined.
     *
     * @param all true for AND, false for OR
     * @param items at least one
     */
    public record Group(boolean all, List<Node> items) implements Node {

        /** Copies the list. */
        public Group {
            items = List.copyOf(items);
        }
    }

    /**
     * One comparison.
     *
     * @param source where the value is read from
     * @param compare how it is compared
     * @param values the values compared with, as text (see {@link SlotCopy}); none, one, two or several by the
     *        comparison
     */
    public record Test(Source source, Compare compare, List<String> values) implements Node {

        /** Copies the list. */
        public Test {
            values = List.copyOf(values);
        }

        /** Prints the shape only. */
        @Override
        public String toString() {
            return "Test[" + source + " " + compare + "]";
        }
    }

    /** Where the value of a field is read from. */
    public sealed interface Source permits Slot, Payload, Column {

        /** The kind of value. */
        ValueKind kind();
    }

    /**
     * A complete slot: the copy is compared, using the index. A number is compared in the slot (double precision) and
     * again exactly in the values of the record.
     *
     * @param column the slot column (from the index policy)
     * @param field the field (for the exact comparison of a number)
     * @param kind the kind of value
     */
    public record Slot(String column, String field, ValueKind kind) implements Source {
    }

    /**
     * The values of the record: no index, every record is read. Used for a field without a complete slot, only when
     * the object is small enough or another condition narrows the records first.
     *
     * @param field the field
     * @param kind the kind of value
     */
    public record Payload(String field, ValueKind kind) implements Source {
    }

    /**
     * A column of the record.
     *
     * @param column which one
     */
    public record Column(SystemColumn column) implements Source {

        @Override
        public ValueKind kind() {
            return switch (column) {
                case CREATED_AT, UPDATED_AT -> ValueKind.TIMESTAMP;
                case ID, OWNER, CREATED_BY, UPDATED_BY -> ValueKind.UUID;
            };
        }
    }

    /**
     * One key of the order.
     *
     * @param source where the value is read from
     * @param descending whether the largest comes first
     */
    public record SortKey(Source source, boolean descending) {
    }

    /**
     * Where the previous page ended.
     *
     * @param values the sort values of the last record, as text, in the order of the keys; a null entry is a record
     *        without a value
     * @param id the identifier of the last record
     */
    public record After(List<String> values, UUID id) {

        /** Copies the list (it may hold nulls). */
        public After {
            values = java.util.Collections.unmodifiableList(new java.util.ArrayList<>(values));
        }

        /** Prints nothing of the values. */
        @Override
        public String toString() {
            return "After[" + id + "]";
        }
    }
}
