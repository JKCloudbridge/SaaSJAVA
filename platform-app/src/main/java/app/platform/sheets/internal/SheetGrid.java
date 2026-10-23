package app.platform.sheets.internal;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The grid of one version as a typed model (ADR-0173, ADR-0186): the only form of a grid the rest of the module sees,
 * so no code reads a map by a string key. Immutable. Everything in it is text of the organization, so every type prints
 * as a redacted marker.
 *
 * @param dimension 1 when the filler adds the rows, 2 when the sheet names the rows
 * @param rowsMode how the rows come about
 * @param columns the columns, at least one, in order
 * @param headerRows the bands above the body, in order
 * @param rows the named body rows, in order (dimension 2 only)
 * @param cells the cells that hold something, by key (row identifier, a colon, column identifier)
 * @param merges the merged areas
 */
record SheetGrid(int dimension, RowsMode rowsMode, List<Column> columns, List<Line> headerRows, List<Line> rows,
        Map<String, Cell> cells, List<Merge> merges) {

    /** The format number this release reads and writes. */
    static final int FORMAT_VERSION = 1;

    SheetGrid {
        columns = List.copyOf(columns);
        headerRows = List.copyOf(headerRows);
        rows = List.copyOf(rows);
        cells = Collections.unmodifiableMap(new LinkedHashMap<>(cells));
        merges = List.copyOf(merges);
    }

    /** How the rows of a sheet come about. */
    enum RowsMode {
        FIXED, REPEATABLE, FIXED_AND_REPEATABLE;

        static Optional<RowsMode> of(String name) {
            if (name == null) {
                return Optional.empty();
            }
            try {
                return Optional.of(valueOf(name));
            } catch (IllegalArgumentException e) {
                return Optional.empty();
            }
        }
    }

    /** What a filler may type into a column. The name in the document is the lower case one. */
    enum ColumnType {
        TEXT, NUMBER, DATE, PICKLIST, BOOLEAN;

        String json() {
            return name().toLowerCase(Locale.ROOT);
        }

        static Optional<ColumnType> of(String json) {
            for (ColumnType type : values()) {
                if (type.json().equals(json)) {
                    return Optional.of(type);
                }
            }
            return Optional.empty();
        }
    }

    /** The four sizes of fixed text; never a number. Absent means {@link #NORMAL}. */
    enum FontSize {
        SMALL, NORMAL, LARGE, TITLE
    }

    /** Whether a body cell is typed into or holds fixed text. */
    enum Kind {
        FIXED, INPUT;

        String json() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * A column.
     *
     * @param id the stable identifier
     * @param label what the column is called
     * @param type what may be typed into it
     * @param required whether a filler must fill it
     * @param defaultValue the value a new row starts with (a string or a boolean), or null
     * @param picklist the values of a picklist column, or null for any other type
     * @param width the width in pixels, or null for the default
     * @param reportable whether the value of this column is copied, in the transaction of every save of a fill-in,
     *        to the table a report reads (FF2, ADR-0193); absent in the document means false
     */
    record Column(String id, String label, ColumnType type, boolean required, Object defaultValue,
            List<String> picklist, Integer width, boolean reportable) {

        Column {
            picklist = picklist == null ? null : List.copyOf(picklist);
        }

        @Override
        public String toString() {
            return "Column[redacted]";
        }
    }

    /**
     * A header row or a named body row.
     *
     * @param id the stable identifier
     * @param label the row label (body rows), or null
     * @param height the height in pixels, or null for the default
     */
    record Line(String id, String label, Integer height) {

        @Override
        public String toString() {
            return "Line[redacted]";
        }
    }

    /**
     * A stored cell.
     *
     * @param value a string or a boolean, or null
     * @param style the style, or null for none
     * @param kind the stated kind, or null when the cell leaves it to the rules
     */
    record Cell(Object value, Style style, Kind kind) {

        @Override
        public String toString() {
            return "Cell[redacted]";
        }
    }

    /**
     * The style of a fixed cell.
     *
     * @param bold bold
     * @param italic italic
     * @param fontSize one of four named sizes, or null for {@link FontSize#NORMAL}
     */
    record Style(boolean bold, boolean italic, FontSize fontSize) {

        /** Whether this changes nothing: no bold, no italic and the normal size. */
        boolean isPlain() {
            return !bold && !italic && (fontSize == null || fontSize == FontSize.NORMAL);
        }
    }

    /**
     * A merged area between two cells.
     *
     * @param from the top left cell
     * @param to the bottom right cell
     */
    record Merge(String from, String to) {

        @Override
        public String toString() {
            return "Merge[redacted]";
        }
    }

    /** Prints a marker, never the content. */
    @Override
    public String toString() {
        return "SheetGrid[redacted]";
    }
}
