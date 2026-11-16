package app.platform.sheets.internal;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The cells of a fill-in as a typed model (FF1, ADR-0192, ADR-0173 section 6, shape B): a list of rows, each with its
 * cells keyed by the identifier of the column. It is the only form of a cells document the rest of the module sees.
 * Immutable. Everything in it is text of the organization, so every type prints as a redacted marker.
 *
 * @param sheetKey the key of the sheet the cells belong to (format 3 only, else null)
 * @param rows the rows in order: the rows the sheet names (in its order) and then the rows the filler added (in the
 *        order the filler gave)
 */
record FillDoc(int formatVersion, String sheetKey, List<Row> rows) {

    /** The original value-only format, retained byte-for-byte; formatted documents explicitly use version 2. */
    static final int FORMAT_VERSION = 1;

    /** The format of a fill-in on a sheet in format version 2 (SH5): the sheet's key, rows, and files by identifier. */
    static final int FORMAT_THREE = 3;

    FillDoc {
        rows = List.copyOf(rows);
    }

    FillDoc(int formatVersion, List<Row> rows) {
        this(formatVersion, null, rows);
    }

    FillDoc(List<Row> rows) {
        this(FORMAT_VERSION, null, rows);
    }

    /**
     * One row.
     *
     * @param id the identifier: that of a row the sheet names, or one the browser generated for a row the filler added
     * @param cells the values by column identifier: a string, a boolean, in an attachment cell of format 3 a list of
     *        file identifiers, or in a Yes/No cell with a reason of format 3 a {@link Choice}; never null or an empty
     *        text
     */
    record Row(String id, Map<String, Object> cells, Map<String, Map<String, Object>> styles) {

        Row {
            cells = Collections.unmodifiableMap(new LinkedHashMap<>(cells));
            var copy = new LinkedHashMap<String, Map<String, Object>>();
            styles.forEach((column, style) -> copy.put(column, Map.copyOf(style)));
            styles = Map.copyOf(copy);
        }

        Row(String id, Map<String, Object> cells) {
            this(id, cells, Map.of());
        }

        @Override
        public String toString() {
            return "Row[redacted]";
        }
    }

    /**
     * The value of a Yes/No cell with a reason (format 3, SH5.1, ADR-0263): the answer and the reason the person typed.
     * Either may be missing, never both. A formula and a report see the answer only; the reason is free text and is
     * read by nothing but the person.
     *
     * @param answer yes, no, or null when not given
     * @param reason the reason, or null; never an empty text
     */
    record Choice(Boolean answer, String reason) {

        /** Whether the cell holds nothing at all. */
        boolean isEmpty() {
            return answer == null && (reason == null || reason.isEmpty());
        }

        @Override
        public String toString() {
            return "Choice[redacted]";
        }
    }

    /** Prints a marker, never the content. */
    @Override
    public String toString() {
        return "FillDoc[redacted]";
    }
}
