package app.platform.sheets.internal;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Maps the name of a font family in a workbook to one of the sixteen keys of the closed list (SH1B, ADR-0251). The
 * name is text from a file: it is compared with a fixed table here, and neither it nor any part of it is ever stored,
 * put in a style or logged. A family the table does not know takes a generic key by a few class words, and the
 * mapping says it was a fallback so the upload can count it.
 */
final class FontKeys {

    /** How a family was mapped. */
    enum How {
        /** The table names this family. */
        EXACT,
        /** The table maps it to the nearest family it has. */
        ALIAS,
        /** Nothing in the table fits: a generic key by class words, or the plain sans-serif one. */
        FALLBACK
    }

    /**
     * The mapping.
     *
     * @param key a key of {@link SheetLook#FONTS}
     * @param how how it was found
     */
    record Mapped(String key, How how) {
    }

    private record Row(String part, String key, How how) {
    }

    /** The table, longest and most specific names first: the first row whose part the name contains wins. */
    private static final List<Row> TABLE = List.of(new Row("arial unicode", "SANS_UNICODE", How.EXACT),
            new Row("arial black", "DISPLAY_HEAVY", How.EXACT), new Row("aharoni", "DISPLAY_HEAVY", How.ALIAS),
            new Row("impact", "DISPLAY_IMPACT", How.EXACT), new Row("arial", "SANS_NEUTRAL", How.EXACT),
            new Row("helvetica", "SANS_NEUTRAL", How.EXACT), new Row("calibri", "SANS_HUMANIST", How.EXACT),
            new Row("segoe ui", "SANS_UI", How.EXACT), new Row("segoe", "SANS_HUMANIST", How.ALIAS),
            new Row("verdana", "SANS_WIDE", How.EXACT),
            new Row("tahoma", "SANS_WIDE", How.ALIAS), new Row("trebuchet", "SANS_FRIENDLY", How.EXACT),
            new Row("times", "SERIF_TEXT", How.EXACT), new Row("georgia", "SERIF_SCREEN", How.EXACT),
            new Row("cambria", "SERIF_SLAB", How.EXACT), new Row("courier", "MONO_CLASSIC", How.EXACT),
            new Row("consolas", "MONO_CODE", How.EXACT), new Row("lucida console", "MONO_CODE", How.ALIAS));

    private static final Pattern MONO = Pattern.compile("mono|courier|code|typewriter");
    private static final Pattern SERIF = Pattern.compile("serif|roman|mincho|song|garamond|palatino|book");

    private FontKeys() {
    }

    /** The key for a family name; a null or blank name takes the plain sans-serif key as a fallback. */
    static Mapped of(String family) {
        String name = family == null ? "" : family.toLowerCase(Locale.ROOT);
        for (Row row : TABLE) {
            if (name.contains(row.part())) {
                return new Mapped(row.key(), row.how());
            }
        }
        if (MONO.matcher(name).find()) {
            return new Mapped("MONO", How.FALLBACK);
        }
        if (SERIF.matcher(name).find() && !name.contains("sans")) {
            return new Mapped("SERIF", How.FALLBACK);
        }
        return new Mapped("SANS", How.FALLBACK);
    }
}
