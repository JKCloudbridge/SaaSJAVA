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
     * result is rewritten in the background by the recalculation (Sprint 16A, ADR-0113), which does not maintain
     * typed copies: a filter or an order on a formula reads the stored result without an index.
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
     * Whether a field is served by a slot: a field an administrator flagged, a field that is unique (the database
     * enforces uniqueness on the typed copy, so a unique field is indexed by being unique), and every master-detail
     * field, which is indexed by the platform because the records that belong to a master are listed by it (a related
     * list) and a junction is read from either of its parents. A master-detail therefore uses one reference slot
     * whether or not it was flagged.
     */
    public static boolean served(FieldDefinition field) {
        return (field.indexed() || field.type() == FieldType.MASTER_DETAIL || enforcesUnique(field))
                && of(field.type()).isPresent();
    }

    /** The most digits of a unique number: a slot holds a double, which tells every number of 15 digits apart. */
    public static final int UNIQUE_NUMBER_DIGITS = 15;

    /**
     * Why a field of the type and setup cannot be unique, in plain words, or empty when it can (Sprint 16A, ADR-0111).
     * The type table says which types may be unique at all; this adds what the check of a slot needs. A number is
     * compared as a double, which is exact up to 15 digits: a wider one could call two different numbers equal. A web
     * address can be longer than an index entry may be.
     */
    public static Optional<String> uniqueProblem(FieldType type, FieldConfiguration configuration) {
        if (type == FieldType.URL) {
            return Optional.of("A web address can be longer than the check for duplicates can handle, so it cannot "
                    + "be unique.");
        }
        int digits = switch (configuration) {
            case FieldConfiguration.NumberConfiguration number -> number.digits();
            case FieldConfiguration.DecimalConfiguration decimal -> decimal.precision();
            case null, default -> 0;
        };
        if (digits > UNIQUE_NUMBER_DIGITS) {
            return Optional.of("A unique number can have at most " + UNIQUE_NUMBER_DIGITS + " digits, so that two "
                    + "different numbers are never taken for the same one.");
        }
        return Optional.empty();
    }

    /**
     * Whether the database enforces the uniqueness of a field: it is flagged unique, its type and setup allow the check
     * and it is neither a system field (the identifier is a column of its own, kept unique by the table) nor an
     * auto-number (the counter hands out a number once and nobody writes the field, so its uniqueness needs no check
     * and no slot).
     */
    public static boolean enforcesUnique(FieldDefinition field) {
        return field.unique() && field.kind() != DefinitionKind.SYSTEM && field.type() != FieldType.AUTO_NUMBER
                && field.type().allowsUnique()
                && of(field.type()).isPresent() && uniqueProblem(field.type(), field.configuration()).isEmpty();
    }
}
