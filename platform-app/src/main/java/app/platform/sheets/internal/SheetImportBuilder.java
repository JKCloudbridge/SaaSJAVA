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
     * @param firstRowLabels whether the first row with text names the columns
     * @param dimension the shape of the sheet: 2 when the sheet names its rows, 1 when the filler adds them
     * @param rowsMode the rows mode that goes with the dimension
     */
    record Options(boolean firstRowLabels, int dimension, RowsMode rowsMode) {
    }

    /**
     * What the person is told about a file that was read.
     *
     * @param columns the columns of the grid
     * @param rows the rows of the grid (0 for a sheet whose filler adds the rows)
     * @param mergedAreas the merged areas that came in
     * @param notes what was left out or came in another form, each with a count
     */
    record Report(int columns, int rows, int mergedAreas, List<ImportNote.Entry> notes) {
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
        int minRow = raw.rows.firstKey();
        int maxRow = raw.rows.lastKey();
        int maxColumn = 0;
        for (NavigableMap<Integer, RawSheet.Cell> row : raw.rows.values()) {
            maxColumn = Math.max(maxColumn, row.lastKey());
        }
        int columnCount = maxColumn + 1;
        if (columnCount > limits.maxColumns()) {
            throw new ImportRefusal("too_many_columns", "The file has " + columnCount + " columns; a sheet may have "
                    + "at most " + limits.maxColumns() + ".");
        }
        boolean dimension2 = options.dimension() == 2;
        int bodyStart = options.firstRowLabels() ? minRow + 1 : minRow;
        int bodyCount = Math.max(0, maxRow - bodyStart + 1);
        if (dimension2 && bodyCount > limits.maxRows()) {
            throw new ImportRefusal("too_many_rows", "The file has " + bodyCount + " rows"
                    + (options.firstRowLabels() ? " below the first" : "") + "; a sheet may have at most "
                    + limits.maxRows() + ".");
        }
        checkTexts(raw, options, bodyStart, dimension2);

        ImportNote.Counts notes = raw.notes;
        List<Column> columns = columns(raw, options, minRow, columnCount);
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
        if (dimension2) {
            merges(raw, bodyStart, maxRow, maxColumn, columns, rowIds, merges, covered, notes);
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
        SheetGrid grid = new SheetGrid(options.dimension(), options.rowsMode(), columns, List.of(), lines, cells,
                merges);
        return new Built(grid, new Report(columns.size(), lines.size(), merges.size(), notes.entries()));
    }

    // ---- the columns ----

    private List<Column> columns(RawSheet raw, Options options, int minRow, int count) {
        List<Column> columns = new ArrayList<>();
        Integer[] widths = new Integer[count];
        for (RawSheet.Width width : raw.widths) {
            for (int c = width.first(); c <= Math.min(width.last(), count - 1); c++) {
                widths[c] = width.pixels();
            }
        }
        NavigableMap<Integer, RawSheet.Cell> labelRow = options.firstRowLabels() ? raw.rows.get(minRow) : null;
        for (int c = 0; c < count; c++) {
            RawSheet.Cell label = labelRow == null ? null : labelRow.get(c);
            String text = label == null ? "Column " + (c + 1) : label.text().strip();
            columns.add(new Column(BlankGrids.identifier("c"), text, ColumnType.TEXT, false, null, null, widths[c],
                    false));
        }
        return columns;
    }

    // ---- merged areas ----

    private void merges(RawSheet raw, int bodyStart, int maxRow, int maxColumn, List<Column> columns,
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
    private void checkTexts(RawSheet raw, Options options, int bodyStart, boolean dimension2) {
        int minRow = raw.rows.firstKey();
        int problems = 0;
        ImportRefusal first = null;
        for (Map.Entry<Integer, java.util.TreeMap<Integer, RawSheet.Cell>> row : raw.rows.entrySet()) {
            boolean label = options.firstRowLabels() && row.getKey() == minRow;
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
