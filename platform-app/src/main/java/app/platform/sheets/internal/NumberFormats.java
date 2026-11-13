package app.platform.sheets.internal;

import java.util.Locale;
import java.util.Set;

/**
 * Turns the number format of a workbook cell into one of the ten keys of the closed list (SH1B, ADR-0251) or says that
 * it cannot. The format code of a file is text from the file: it is compared with fixed patterns here and never stored
 * or used as it came.
 *
 * <p>A date or a time is a different case: the upload keeps such a cell as its text (year-month-day) and the key is
 * only a hint for the screen, so a date format that has no key is not a loss and is not counted.
 */
final class NumberFormats {

    /** The built-in format numbers that show a date or a time (the ones a spreadsheet program defines itself). */
    private static final Set<Integer> BUILT_IN_DATES = Set.of(14, 15, 16, 17, 18, 19, 20, 21, 22, 27, 28, 29, 30, 31,
            32, 33, 34, 35, 36, 45, 46, 47, 50, 51, 52, 53, 54, 55, 56, 57, 58);

    /**
     * What a format means for the upload.
     *
     * @param key a key of {@link SheetLook#NUMBER_FORMATS} other than GENERAL, or null for the general format
     * @param date whether the format shows a date or a time
     * @param unknown whether the format is a number format the closed list cannot hold
     */
    record Meaning(String key, boolean date, boolean unknown) {

        static final Meaning GENERAL = new Meaning(null, false, false);
    }

    /** Stands for text a format shows as it is (a quoted word, an escaped character, a currency block). */
    private static final char LITERAL = '\u0001';

    private NumberFormats() {
    }

    /**
     * The meaning of a format.
     *
     * @param id the number of the format (built-in below 164, otherwise stated by the file)
     * @param code the format code the file states (or the library states for a built-in one), or null
     */
    static Meaning of(int id, String code) {
        if (id == 0 || code != null && "general".equalsIgnoreCase(code.strip())) {
            return Meaning.GENERAL;
        }
        if (code == null) {
            return BUILT_IN_DATES.contains(id) ? new Meaning(null, true, false) : Meaning.GENERAL;
        }
        if (id >= 41 && id <= 44) {
            return new Meaning("ACCOUNTING", false, false);
        }
        String normal = normalise(code);
        if (normal.equals("@") || normal.isEmpty()) {
            return Meaning.GENERAL;
        }
        if (isDateFormat(code)) {
            return new Meaning(dateKey(normal), true, false);
        }
        String first = firstSection(normal);
        String key = switch (first) {
            case "0" -> "WHOLE";
            case "0.00" -> "TWO_DECIMALS";
            case "#,##0" -> "THOUSANDS";
            case "#,##0.00" -> "THOUSANDS_TWO_DECIMALS";
            case "0%" -> "PERCENT";
            case "0.00%" -> "PERCENT_TWO_DECIMALS";
            default -> null;
        };
        if (key == null && normal.contains("(*") && normal.contains("#,##0")) {
            key = "ACCOUNTING";
        }
        return key == null ? new Meaning(null, false, true) : new Meaning(key, false, false);
    }

    private static String dateKey(String normal) {
        if (normal.indexOf('d') >= 0) {
            return "SHORT_DATE";
        }
        return normal.indexOf('m') >= 0 && normal.indexOf('y') >= 0 ? "MONTH_YEAR" : null;
    }

    /**
     * The code in lower case without locale blocks, colours, quotes and escapes, and without the padding of a section.
     */
    private static String normalise(String code) {
        StringBuilder out = new StringBuilder();
        boolean quoted = false;
        boolean bracket = false;
        String lower = code.strip().toLowerCase(Locale.ROOT);
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (quoted) {
                quoted = c != '"';
            } else if (bracket) {
                bracket = c != ']';
            } else if (c == '"') {
                // A literal (a currency sign, a unit) is not in the closed list: it must stop a match below.
                quoted = true;
                out.append(LITERAL);
            } else if (c == '[') {
                bracket = true;
                if (i + 1 < lower.length() && lower.charAt(i + 1) == '$') {
                    out.append(LITERAL);
                }
            } else if (c == '\\') {
                i++;
                out.append(LITERAL);
            } else if (c == '_') {
                i++;
            } else if (c == '*') {
                out.append("(*");
                i++;
            } else if (c != ' ') {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static String firstSection(String normal) {
        int semicolon = normal.indexOf(';');
        return semicolon < 0 ? normal : normal.substring(0, semicolon);
    }

    /** Whether a number format shows a date or a time: it names a day, month, year, hour or second outside quotes. */
    static boolean isDateFormat(String code) {
        StringBuilder bare = new StringBuilder();
        boolean quoted = false;
        boolean bracket = false;
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            if (quoted) {
                quoted = c != '"';
            } else if (bracket) {
                bracket = c != ']';
            } else if (c == '"') {
                quoted = true;
            } else if (c == '[') {
                bracket = true;
            } else if (c == '\\' || c == '_' || c == '*') {
                i++;
            } else {
                bare.append(Character.toLowerCase(c));
            }
        }
        String text = bare.toString();
        return text.indexOf('d') >= 0 || text.indexOf('y') >= 0 || text.indexOf('h') >= 0
                || text.indexOf('s') >= 0 || text.indexOf('m') >= 0;
    }
}
