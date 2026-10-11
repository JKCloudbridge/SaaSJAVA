package app.platform.expression;

/**
 * The kinds of value an expression works with. A field of the platform has one of these as far as an expression is
 * concerned ({@code DECIMAL}, {@code CURRENCY} and {@code PERCENT} fields are all {@link #NUMBER}; e-mail, phone, URL,
 * picklist and the identifier of a lookup are all {@link #TEXT}).
 */
public enum ExpressionType {

    /** True or false. */
    BOOLEAN("a checkbox (true or false)"),

    /** A decimal number. */
    NUMBER("a number"),

    /** Text. The empty text does not exist: it is the same as no value. */
    TEXT("text"),

    /** A calendar day. */
    DATE("a date"),

    /** A day and a time of day, in UTC. */
    DATETIME("a date and time"),

    /** A time of day. */
    TIME("a time"),

    /** The type of the word NULL: no value, which fits wherever a value is expected. */
    NONE("no value");

    private final String words;

    ExpressionType(String words) {
        this.words = words;
    }

    /** The words a message uses for this type, for example "a number". */
    public String words() {
        return words;
    }
}
