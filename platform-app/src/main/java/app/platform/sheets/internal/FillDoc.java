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
 * @param rows the rows in order: the rows the sheet names (in its order) and then the rows the filler added (in the
 *        order the filler gave)
 */
record FillDoc(List<Row> rows) {

    /** The format number this release reads and writes. */
    static final int FORMAT_VERSION = 1;

    FillDoc {
        rows = List.copyOf(rows);
    }

    /**
     * One row.
     *
     * @param id the identifier: that of a row the sheet names, or one the browser generated for a row the filler added
     * @param cells the values by column identifier: a string or a boolean, never null or an empty text
     */
    record Row(String id, Map<String, Object> cells) {

        Row {
            cells = Collections.unmodifiableMap(new LinkedHashMap<>(cells));
        }

        @Override
        public String toString() {
            return "Row[redacted]";
        }
    }

    /** Prints a marker, never the content. */
    @Override
    public String toString() {
        return "FillDoc[redacted]";
    }
}
