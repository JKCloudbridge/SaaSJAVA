package app.platform.sheets.internal;

import app.platform.sheets.internal.SheetGrid.Cell;
import app.platform.sheets.internal.SheetGrid.Column;
import app.platform.sheets.internal.SheetGrid.ColumnType;
import app.platform.sheets.internal.SheetGrid.FontSize;
import app.platform.sheets.internal.SheetGrid.Line;
import app.platform.sheets.internal.SheetGrid.Merge;
import app.platform.sheets.internal.SheetGrid.RowsMode;
import app.platform.sheets.internal.SheetGrid.Style;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;

/**
 * Turns what a reader found into a grid, by the rules the author was told (UI3B, ADR-0195): the first row names the
 * columns (when asked), every text of the file is fixed text of the sheet, a cell with no text is left to be typed
 * into, widths, heights and merged areas come with it, and what the sheet cannot hold is counted. The result is not
 * trusted: {@link SheetGridValidator} runs on it as on every grid, and this class checks only what it can say by
 * <em>position in the file</em> (a row number and a column letter), which the validator, naming generated identifiers,
 * cannot. Nothing of the file is put in a message.
 */
final class SheetImportBuilder {

    /**
     * How the grid is made.
     *
     * @param firstRowLabels whether the first row of the table names the columns; null leaves the choice to the
     *         server, which says no when that row is a title (FA14) and yes otherwise
     * @param dimension the shape of the sheet: 2 when the sheet names its rows, 1 when the filler adds them
     * @param rowsMode the rows mode that goes with the dimension
     */
    record Options(Boolean firstRowLabels, int dimension, RowsMode rowsMode) {
    }

    /**
     * What the person is told about a file that was read.
     *
     * @param columns the columns of the grid
     * @param rows the rows of the grid (0 for a sheet whose filler adds the rows)
     * @param mergedAreas the merged areas that came in
     * @param notes what was left out or came in another form, each with a count
     * @param firstRowLabels whether the first row names the columns in this grid (the choice that was applied)
     * @param firstRowIsTitle whether the first row of the table is a title: one merged area across its whole width
     */
    record Report(int columns, int rows, int mergedAreas, List<ImportNote.Entry> notes, boolean firstRowLabels,
            boolean firstRowIsTitle) {
    }

    /**
     * The grid and its report.
     *
     * @param grid the grid, not yet checked by the validator
     * @param report what the person is told
     */
    record Built(SheetGrid grid, Report report) {

        @Override
        public String toString() {
            return "Built[redacted]";
        }
    }

    private final SheetProperties.Limits limits;

    SheetImportBuilder(SheetProperties.Limits limits) {
        this.limits = limits;
    }

    Built build(RawSheet raw, Options options) {
        if (raw.isEmpty()) {
            throw new ImportRefusal("empty_file", "The file holds no text, so there is nothing to make a sheet "
                    + "from.");
        }
        // The table is every cell with text or a border (FA14, ADR-0250): a bordered blank cell extends it, a blank
        // cell with no border does not.
        RawSheet.Extent extent = raw.extent();
        int minRow = extent.top();
        int maxRow = extent.bottom();
        int maxColumn = extent.right();
        int columnCount = maxColumn + 1;
        if (columnCount > limits.maxColumns()) {
            throw tooManyColumns(columnCount, limits.maxColumns());
        }
        boolean title = isTitle(raw, extent);
        boolean labels = options.firstRowLabels() == null ? !title : options.firstRowLabels();
        boolean dimension2 = options.dimension() == 2;
        int bodyStart = labels ? minRow + 1 : minRow;
        int bodyCount = Math.max(0, maxRow - bodyStart + 1);
        if (dimension2 && bodyCount > limits.maxRows()) {
            throw new ImportRefusal("too_many_rows", "The file has " + bodyCount + " rows"
                    + (labels ? " below the first" : "") + "; a sheet may have at most " + limits.maxRows() + ".");
        }
        checkTexts(raw, labels, minRow, bodyStart, dimension2);

        ImportNote.Counts notes = raw.notes;
        List<Column> columns = columns(raw, labels, minRow, columnCount);
        List<String> rowIds = new ArrayList<>();
        List<Line> lines = new ArrayList<>();
        if (dimension2) {
            for (int i = 0; i < bodyCount; i++) {
                String id = BlankGrids.identifier("r");
                rowIds.add(id);
                lines.add(new Line(id, null, raw.heights.get(bodyStart + i)));
            }
        } else {
            int dropped = 0;
            for (Integer row : raw.rows.keySet()) {
                if (row >= bodyStart) {
                    dropped++;
                }
            }
            notes.add(ImportNote.ROWS_NOT_KEPT, dropped);
        }
        List<Merge> merges = new ArrayList<>();
        Set<Long> covered = new HashSet<>();
        List<RawSheet.Area> accepted = List.of();
        if (dimension2) {
            accepted = merges(raw, bodyStart, maxRow, maxColumn, columns, rowIds, merges, covered, notes);
        } else {
            notes.add(ImportNote.MERGES_LEFT_OUT, raw.merges.size());
        }
        Map<String, Cell> cells = new LinkedHashMap<>();
        if (dimension2) {
            for (Map.Entry<Integer, java.util.TreeMap<Integer, RawSheet.Cell>> row : raw.rows.tailMap(bodyStart)
                    .entrySet()) {
                for (Map.Entry<Integer, RawSheet.Cell> cell : row.getValue().entrySet()) {
                    if (covered.contains(key(row.getKey(), cell.getKey()))) {
                        continue;
                    }
                    RawSheet.Cell found = cell.getValue();
                    String id = rowIds.get(row.getKey() - bodyStart) + ":" + columns.get(cell.getKey()).id();
                    cells.put(id, new Cell(found.text(), style(found), null));
                }
            }
        }
        if (dimension2) {
            notes.add(ImportNote.BORDERED_BLANKS, blankBorderedCells(raw, bodyStart, accepted));
            if (title && !labels) {
                notes.add(ImportNote.TITLE_ROW);
            }
        }
        SheetGrid grid = new SheetGrid(options.dimension(), options.rowsMode(), columns, List.of(), lines, cells,
                merges);
        return new Built(grid, new Report(columns.size(), lines.size(), merges.size(), notes.entries(), labels,
                title));
    }

    /** The refusal for a table wider than a sheet may be, shared with the reader that stops a file early. */
    static ImportRefusal tooManyColumns(int count, int most) {
        return new ImportRefusal("too_many_columns", "The file has " + count + " columns; a sheet may have at most "
                + most + ".");
    }

    /**
     * Whether the first row of the table is a title (FA14, ADR-0250): the only text in that row is in its first
     * column, and that cell is a merged area of this one row that spans the whole width of the table, which is at
     * least two columns wide. A merge over part of the width is an ordinary row.
     */
    private static boolean isTitle(RawSheet raw, RawSheet.Extent extent) {
        NavigableMap<Integer, RawSheet.Cell> first = raw.rows.get(extent.top());
        if (extent.right() < 1 || first == null || first.size() != 1 || first.firstKey() != 0) {
            return false;
        }
        for (RawSheet.Area area : raw.merges) {
            if (area.top() == extent.top() && area.bottom() == extent.top() && area.left() == 0
                    && area.right() == extent.right()) {
                return true;
            }
        }
        return false;
    }

    /**
     * How many cells with a border and no text lie in the rows that become the body of the sheet, not counting the
     * ones under a merged area that came in (a merged area is one cell, its top left).
     */
    private static int blankBorderedCells(RawSheet raw, int bodyStart, List<RawSheet.Area> accepted) {
        int count = 0;
        for (java.util.BitSet columns : raw.bordered.tailMap(bodyStart).values()) {
            count += columns.cardinality();
        }
        for (RawSheet.Area area : accepted) {
            for (Map.Entry<Integer, java.util.BitSet> row : raw.bordered
                    .subMap(area.top(), true, area.bottom(), true).entrySet()) {
                int inside = row.getValue().get(area.left(), area.right() + 1).cardinality();
                boolean anchor = row.getKey() == area.top() && row.getValue().get(area.left());
                count -= inside - (anchor ? 1 : 0);
            }
        }
        return count;
    }

    // ---- the columns ----

    private List<Column> columns(RawSheet raw, boolean labels, int minRow, int count) {
        List<Column> columns = new ArrayList<>();
        Integer[] widths = new Integer[count];
        for (RawSheet.Width width : raw.widths) {
            for (int c = width.first(); c <= Math.min(width.last(), count - 1); c++) {
                widths[c] = width.pixels();
            }
        }
        NavigableMap<Integer, RawSheet.Cell> labelRow = labels ? raw.rows.get(minRow) : null;
        for (int c = 0; c < count; c++) {
            RawSheet.Cell label = labelRow == null ? null : labelRow.get(c);
            String text = label == null ? "Column " + (c + 1) : label.text().strip();
            columns.add(new Column(BlankGrids.identifier("c"), text, ColumnType.TEXT, false, null, null, widths[c],
                    false));
        }
        return columns;
    }

    // ---- merged areas ----

    private List<RawSheet.Area> merges(RawSheet raw, int bodyStart, int maxRow, int maxColumn, List<Column> columns,
            List<String> rowIds, List<Merge> merges, Set<Long> covered, ImportNote.Counts notes) {
        List<RawSheet.Area> accepted = new ArrayList<>();
        int leftOut = 0;
        int under = 0;
        for (RawSheet.Area area : raw.merges) {
            boolean outside = area.top() < bodyStart || area.bottom() > maxRow || area.right() > maxColumn;
            if (outside || overlaps(accepted, area)) {
                leftOut++;
                continue;
            }
            accepted.add(area);
            merges.add(new Merge(rowIds.get(area.top() - bodyStart) + ":" + columns.get(area.left()).id(),
                    rowIds.get(area.bottom() - bodyStart) + ":" + columns.get(area.right()).id()));
            for (Map.Entry<Integer, java.util.TreeMap<Integer, RawSheet.Cell>> row
                    : raw.rows.subMap(area.top(), true, area.bottom(), true).entrySet()) {
                for (Integer column : row.getValue().subMap(area.left(), true, area.right(), true).keySet()) {
                    if (row.getKey() != area.top() || column != area.left()) {
                        covered.add(key(row.getKey(), column));
                        under++;
                    }
                }
            }
        }
        notes.add(ImportNote.MERGES_LEFT_OUT, leftOut);
        notes.add(ImportNote.COVERED_CELLS, under);
        return accepted;
    }

    private static boolean overlaps(List<RawSheet.Area> accepted, RawSheet.Area area) {
        for (RawSheet.Area other : accepted) {
            if (area.top() <= other.bottom() && area.bottom() >= other.top() && area.left() <= other.right()
                    && area.right() >= other.left()) {
                return true;
            }
        }
        return false;
    }

    private static long key(int row, int column) {
        return ((long) row << 32) | column;
    }

    // ---- what a position can say ----

    /** Refuses the first text that is too long or unsafe, naming its row and column in the file. */
    private void checkTexts(RawSheet raw, boolean labels, int minRow, int bodyStart, boolean dimension2) {
        int problems = 0;
        ImportRefusal first = null;
        for (Map.Entry<Integer, java.util.TreeMap<Integer, RawSheet.Cell>> row : raw.rows.entrySet()) {
            boolean label = labels && row.getKey() == minRow;
            if (!label && (!dimension2 || row.getKey() < bodyStart)) {
                continue;
            }
            int most = label ? limits.maxLabelChars() : limits.maxCellChars();
            for (Map.Entry<Integer, RawSheet.Cell> cell : row.getValue().entrySet()) {
                String text = cell.getValue().text();
                String where = "Row " + (row.getKey() + 1) + ", column " + letters(cell.getKey());
                ImportRefusal found = null;
                if (text.length() > most) {
                    found = new ImportRefusal("text_too_long", where + " has a text longer than the allowed " + most
                            + " characters.");
                } else if (CellValues.unsafe(text)) {
                    found = new ImportRefusal("unsafe_text", where + " holds markup or a control character, which a "
                            + "sheet does not accept.");
                }
                if (found != null) {
                    problems++;
                    if (first == null) {
                        first = found;
                    }
                }
            }
        }
        if (first != null) {
            throw problems == 1 ? first : new ImportRefusal(first.code(), first.words() + " " + (problems - 1)
                    + " more place" + (problems == 2 ? " is" : "s are") + " also wrong.");
        }
    }

    /** The letters of a column, from zero: A, B, ... Z, AA. */
    static String letters(int column) {
        StringBuilder out = new StringBuilder();
        int n = column + 1;
        while (n > 0) {
            int rest = (n - 1) % 26;
            out.insert(0, (char) ('A' + rest));
            n = (n - 1) / 26;
        }
        return out.toString();
    }

    private static Style style(RawSheet.Cell cell) {
        FontSize size = cell.points() <= 0 ? null : cell.points() <= 9 ? FontSize.SMALL
                : cell.points() <= 12 ? null : cell.points() <= 16 ? FontSize.LARGE : FontSize.TITLE;
        if (!cell.bold() && !cell.italic() && size == null) {
            return null;
        }
        return new Style(cell.bold(), cell.italic(), size);
    }
}
