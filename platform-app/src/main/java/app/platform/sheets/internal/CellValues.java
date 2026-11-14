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

    /** A file is named by the identifier the file service gave it: a UUID. */
    private static final Pattern FILE_ID = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

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

    /**
     * Why the value does not fit the cell, or null when it does (format 3, SH5). The kinds are the column's plus the
     * attachment: a list of at most {@code maxFiles} identifiers, none repeated. Nothing is checked about the bytes of
     * a
     * file; that the identifiers belong to this fill-in's header is the file service's fact, asked at save.
     */
    static String problem(FillStructure.Effective cell, Object value) {
        return switch (cell.type()) {
            case TEXT -> value instanceof String ? null : "A text cell holds text.";
            case NUMBER -> value instanceof String text && NUMBER.matcher(text).matches() ? null
                    : "A number cell holds digits, with an optional sign and decimals.";
            case DATE -> value instanceof String text && isDate(text) ? null
                    : "A date cell holds a date written year-month-day.";
            case BOOLEAN -> value instanceof Boolean ? null : "A yes or no cell holds yes or no.";
            case PICKLIST -> value instanceof String text && cell.picklist() != null
                    && cell.picklist().contains(text) ? null : "A list cell holds one of its listed values.";
            case ATTACHMENT -> attachmentProblem(cell, value);
        };
    }

    private static String attachmentProblem(FillStructure.Effective cell, Object value) {
        if (!(value instanceof java.util.List<?> files)) {
            return "An attachment cell holds a list of files.";
        }
        if (files.size() > cell.maxFiles()) {
            return "An attachment cell holds at most " + cell.maxFiles() + " files.";
        }
        java.util.Set<Object> seen = new java.util.HashSet<>();
        for (Object file : files) {
            if (!(file instanceof String id) || !FILE_ID.matcher(id).matches()) {
                return "An attachment cell names a file in a way that is not allowed.";
            }
            if (!seen.add(id)) {
                return "An attachment cell names a file more than once.";
            }
        }
        return null;
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
