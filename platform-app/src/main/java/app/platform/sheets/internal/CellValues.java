package app.platform.sheets.internal;

import app.platform.sheets.internal.SheetGrid.Column;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.regex.Pattern;

/**
 * The rules for the value of a cell, in one place (ADR-0173 section 2): what text is clean, and what each column type
 * accepts. The browser has the same rules for the person's convenience (the file {@code values.ts} of the grid); the
 * server decides again. The words are fixed and never repeat the value.
 */
final class CellValues {

    /** A number travels as the digits typed (ADR-0129): an optional sign, up to 38 digits, up to 18 decimals. */
    private static final Pattern NUMBER = Pattern.compile("-?[0-9]{1,38}(\\.[0-9]{1,18})?");
    private static final Pattern DATE = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}");
    /** A less-than sign followed by a letter, a slash, an exclamation mark or a question mark starts markup. */
    private static final Pattern MARKUP = Pattern.compile("<[A-Za-z/!?]");
    /**
     * Control characters other than tab and line feed (a cell may hold a few lines), the line and paragraph
     * separators, and the characters that reorder how text is drawn (a way to make text look like something else).
     */
    private static final Pattern CONTROL = Pattern.compile(
            "[\\u0000-\\u0008\\u000b\\u000c\\u000e-\\u001f\\u007f-\\u009f\\u2028\\u2029"
                    + "\\u202a-\\u202e\\u2066-\\u2069]");

    static final String TEXT_UNSAFE = "A text contains markup or a control character.";

    private CellValues() {
    }

    /** Whether the text holds markup or a control character; it is refused, never escaped. */
    static boolean unsafe(String text) {
        return MARKUP.matcher(text).find() || CONTROL.matcher(text).find();
    }

    /**
     * Why the value does not fit the column, or null when it does. {@code value} is a string or a boolean.
     */
    static String problem(Column column, Object value) {
        return switch (column.type()) {
            case TEXT -> value instanceof String ? null : "A text column holds text.";
            case NUMBER -> value instanceof String text && NUMBER.matcher(text).matches() ? null
                    : "A number column holds digits, with an optional sign and decimals.";
            case DATE -> value instanceof String text && isDate(text) ? null
                    : "A date column holds a date written year-month-day.";
            case BOOLEAN -> value instanceof Boolean ? null : "A yes or no column holds yes or no.";
            case PICKLIST -> value instanceof String text && column.picklist() != null
                    && column.picklist().contains(text) ? null : "A list column holds one of its listed values.";
            default -> "Unknown column type.";
        };
    }

    private static boolean isDate(String text) {
        if (!DATE.matcher(text).matches()) {
            return false;
        }
        try {
            LocalDate.parse(text);
            return true;
        } catch (DateTimeException e) {
            return false;
        }
    }
}
