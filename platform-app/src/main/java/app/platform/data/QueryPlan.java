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
    public sealed interface Node permits Group, Test, OwnedByAny, SharedWithAny, NoRecords {
    }

    /**
     * The record is owned by one of the users (record-level security, Sprint 17, ADR-0119). The owner is a column of
     * the record with its own index.
     *
     * @param owners at least one user identifier, in a fixed order so that the same set gives the same statement
     */
    public record OwnedByAny(List<UUID> owners) implements Node {

        /** Copies the list. */
        public OwnedByAny {
            owners = List.copyOf(owners);
        }

        /** Prints the size only. */
        @Override
        public String toString() {
            return "OwnedByAny[" + owners.size() + "]";
        }
    }

    /**
     * One grantee of a share, as the share table stores it: a kind and an identifier (Functional track FA2B,
     * ADR-0185). A share matches when both are equal, never the identifier alone.
     *
     * @param type {@code USER}, {@code GROUP}, {@code ROLE} or {@code ROLE_AND_SUBORDINATES}
     * @param id the user, group or role
     */
    public record ShareGrantee(String type, UUID id) {
    }

    /**
     * The record is shared with one of the grantees at least at the level (explicit shares, Sprint 17, ADR-0117,
     * ADR-0185).
     *
     * @param grantees at least one grantee, in a fixed order
     * @param level the least level of the share: 1 read, 2 read and write
     */
    public record SharedWithAny(List<ShareGrantee> grantees, int level) implements Node {

        /** Copies the list. */
        public SharedWithAny {
            grantees = List.copyOf(grantees);
        }

        /** Prints the size only. */
        @Override
        public String toString() {
            return "SharedWithAny[" + grantees.size() + ", " + level + "]";
        }
    }

    /** No record at all. */
    public record NoRecords() implements Node {
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
