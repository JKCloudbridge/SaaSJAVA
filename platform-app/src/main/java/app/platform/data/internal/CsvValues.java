package app.platform.data.internal;

import app.platform.metadata.FieldDefinition;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Turns a cell of an import file into the value the write pipeline takes for a field (Sprint 17, ADR-0123). It does
 * the reading of text only; whether the value belongs in the field (a day that exists, a picklist value that is on,
 * an e-mail address) is decided by the pipeline, exactly as for a record written through the API, so a file cannot
 * put in what the API refuses.
 *
 * <p>A number is plain digits with an optional sign and decimal point (no exponent, no thousands separator); true and
 * false are written {@code true} and {@code false} in any case; a multi-select picklist separates its values with
 * semicolons; a day is {@code 2030-01-31}, a day and time {@code 2030-01-31T09:30:00Z}, a time {@code 09:30}; a link is
 * the identifier of the other record, or, when the column is mapped that way, the value of the other record's external
 * identifier. A cell that is empty is no value (the caller leaves the field out).
 *
 * <p>The messages say what is expected and never repeat the cell.
 */
final class CsvValues {

    /** Why a cell cannot be read as the value of its field. */
    static final class Problem extends RuntimeException {

        private static final long serialVersionUID = 1L;

        Problem(String message) {
            super(message, null, false, false);
        }
    }

    private static final Pattern NUMBER = Pattern.compile("[+-]?[0-9]{1,38}(\\.[0-9]{1,38})?");

    private CsvValues() {
    }

    /**
     * The value for the cell.
     *
     * @param field the field
     * @param cell the cell, not empty
     * @param lookup for a link written as the external identifier of the other record: finds its identifier, or
     *        throws {@link Problem}; null when the link is written as an identifier
     */
    static Object convert(FieldDefinition field, String cell, Function<String, String> lookup) {
        String text = cell.strip();
        return switch (FieldValue.effectiveType(field)) {
            case NUMBER, DECIMAL, CURRENCY, PERCENT -> number(text);
            case BOOLEAN -> bool(text);
            case MULTI_PICKLIST -> multi(text);
            case LOOKUP, MASTER_DETAIL -> lookup == null ? text : lookup.apply(text);
            case TEXT, LONG_TEXT -> cell;
            default -> text;
        };
    }

    private static BigDecimal number(String text) {
        if (!NUMBER.matcher(text).matches()) {
            throw new Problem("Enter a number such as 12 or -3.5.");
        }
        return new BigDecimal(text);
    }

    private static Boolean bool(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        if ("true".equals(lower)) {
            return Boolean.TRUE;
        }
        if ("false".equals(lower)) {
            return Boolean.FALSE;
        }
        throw new Problem("Enter true or false.");
    }

    private static List<String> multi(String text) {
        List<String> values = new ArrayList<>();
        for (String part : text.split(";", -1)) {
            String value = part.strip();
            if (!value.isEmpty()) {
                values.add(value);
            }
        }
        return values;
    }
}
