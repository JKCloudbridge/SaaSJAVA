package app.platform.metadata;

import java.util.Optional;

/**
 * The kinds of physical column ("slot") of the record table that can hold the indexed copy of a field (Sprint 16,
 * ADR-0101), and how many of each the table has. The number is a hard limit of the design: it is the number of
 * fields of that kind an object can flag as indexed.
 *
 * <p>The counts here and the columns of the record table (migration V041) must agree; a test of the data module
 * compares them.
 */
public enum SlotClass {

    /** Text, e-mail, phone, web address, picklist and auto-number values: text compared exactly. */
    TEXT("text", 8),

    /** Whole numbers, decimals, currency and percent: a double precision copy, always rechecked exactly. */
    NUMBER("number", 4),

    /** A calendar day. */
    DATE("date", 2),

    /** A day with a time of day. */
    TIMESTAMP("date and time", 2),

    /** The identifier of the record a lookup or master-detail field points at. */
    REFERENCE("link", 4),

    /** Yes or no. */
    BOOLEAN("checkbox", 2);

    private final String words;
    private final int capacity;

    SlotClass(String words, int capacity) {
        this.words = words;
        this.capacity = capacity;
    }

    /** How the kind is named to a person in an error message. */
    public String words() {
        return words;
    }

    /** How many slots of this kind the record table has. */
    public int capacity() {
        return capacity;
    }

    /**
     * The kind of slot a field of the type would use, or empty when a field of that type cannot be indexed: long
     * text and multi-select picklists are too big or not single values, a time of day has no column, and a formula
     * result is stored on write only, so an index on it would be stale until the recalculation of Sprint 16A.
     */
    public static Optional<SlotClass> of(FieldType type) {
        return Optional.ofNullable(switch (type) {
            case TEXT, EMAIL, PHONE, URL, PICKLIST, AUTO_NUMBER -> TEXT;
            case NUMBER, DECIMAL, CURRENCY, PERCENT -> NUMBER;
            case DATE -> DATE;
            case DATETIME -> TIMESTAMP;
            case LOOKUP, MASTER_DETAIL -> REFERENCE;
            case BOOLEAN -> BOOLEAN;
            case LONG_TEXT, MULTI_PICKLIST, TIME, FORMULA -> null;
        });
    }

    /**
     * Whether a field is served by a slot: a field an administrator flagged, and every master-detail field, which is
     * indexed by the platform because the records that belong to a master are listed by it (a related list) and a
     * junction is read from either of its parents. A master-detail therefore uses one reference slot whether or not it
     * was flagged.
     */
    public static boolean served(FieldDefinition field) {
        return (field.indexed() || field.type() == FieldType.MASTER_DETAIL) && of(field.type()).isPresent();
    }
}
