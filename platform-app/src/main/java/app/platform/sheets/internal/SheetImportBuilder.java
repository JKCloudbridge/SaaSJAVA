package app.platform.sheets.internal;

import app.platform.sheets.internal.SheetDocument.Condition;
import app.platform.sheets.internal.SheetDocument.Freeze;
import app.platform.sheets.internal.SheetDocument.Input;
import app.platform.sheets.internal.SheetDocument.InputClass;
import app.platform.sheets.internal.SheetDocument.Print;
import app.platform.sheets.internal.SheetDocument.Tab;
import app.platform.sheets.internal.SheetGrid.Alignment;
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
import java.util.TreeMap;

/**
 * Turns what a reader found into a grid, by the rules the author was told (UI3B, ADR-0195): the first row names the
 * columns (when asked), every text of the file is fixed text of the sheet, a cell with no text is left to be typed
 * into, widths, heights and merged areas come with it, and what the sheet cannot hold is counted. The result is not
 * trusted: {@link SheetGridValidator} runs on it as on every grid, and this class checks only what it can say by
 * <em>position in the file</em> (a row number and a column letter), which the validator, naming generated identifiers,
 * cannot. Nothing of the file is put in a message.
 *
 * <p><strong>Format version 2 (SH1B, ADR-0253).</strong> With {@link Options#formatTwo()} the same grid is made and a
 * document of format version 2 is made around it: the look of each cell, links, yes/no cells from check boxes,
 * conditional colours, the frozen panes and the page set-up, with the limits of version 2 (a fixed text up to 5,000
 * characters, narrower columns and shorter rows). The rules of ADR-0250 for the extent of the table, bordered blank
 * cells and the title row are the same for both. Without the flag nothing of version 2 is read into the grid and the
 * grid is what it always was.
 */
final class SheetImportBuilder {

    /**
     * How the grid is made.
     *
     * @param firstRowLabels whether the first row of the table names the columns; null leaves the choice to the
     *         server, which says no when that row is a title (FA14) and yes otherwise
     * @param dimension the shape of the sheet: 2 when the sheet names its rows, 1 when the filler adds them
     * @param rowsMode the rows mode that goes with the dimension
     * @param worksheet the worksheet to read, as its number among the visible ones from zero, or null for the first
     *         that holds anything (a text file has one)
     * @param formatTwo whether to make a document of format version 2
     */
    record Options(Boolean firstRowLabels, int dimension, RowsMode rowsMode, Integer worksheet, boolean formatTwo) {

        Options(Boolean firstRowLabels, int dimension, RowsMode rowsMode) {
            this(firstRowLabels, dimension, rowsMode, null, false);
        }
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
     * @param worksheets the visible worksheets of a workbook, empty for a text file
     * @param worksheet the worksheet that was read, among the visible ones, or -1 for a text file
     * @param embeddedFilePlaces the cells where an embedded file stood, at most a hundred
     */
    record Report(int columns, int rows, int mergedAreas, List<ImportNote.Entry> notes, boolean firstRowLabels,
            boolean firstRowIsTitle, List<RawSheet.Worksheet> worksheets, int worksheet,
            List<RawSheet.Place> embeddedFilePlaces) {
    }

    /**
     * The grid and its report.
     *
     * @param grid the grid, not yet checked by the validator (the body of the document in format version 2)
     * @param document the document of format version 2 around the grid, or null for format version 1
     * @param report what the person is told
     */
    record Built(SheetGrid grid, SheetDocument document, Report report) {

        Built(SheetGrid grid, Report report) {
            this(grid, null, report);
        }

        @Override
        public String toString() {
            return "Built[redacted]";
        }
    }

    private static final int MOST_PLACES = 100;
    private static final int MOST_CONDITIONS = 50;
    private static final int MOST_FREEZE_ROWS = 50;
    private static final int MOST_FREEZE_COLUMNS = 20;
    /** The key of the one sheet a template holds for now. */
    private static final String SHEET_KEY = "main";

    private final SheetProperties.Limits limits;

    SheetImportBuilder(SheetProperties.Limits limits) {
        this.limits = limits;
    }

    Built build(RawSheet raw, Options options) {
        if (raw.isEmpty()) {
            throw new ImportRefusal("empty_file", "The file holds no text, so there is nothing to make a sheet "
                    + "from.");
        }
        boolean two = options.formatTwo();
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
        checkTexts(raw, labels, minRow, bodyStart, dimension2, two ? limits.maxFixedTextChars()
                : limits.maxCellChars());

        ImportNote.Counts notes = raw.notes;
        List<Column> columns = columns(raw, labels, minRow, columnCount, two);
        List<String> rowIds = new ArrayList<>();
        List<Line> lines = new ArrayList<>();
        if (dimension2) {
            for (int i = 0; i < bodyCount; i++) {
                String id = BlankGrids.identifier("r");
                rowIds.add(id);
                lines.add(new Line(id, null, height(raw, bodyStart + i, two)));
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
        Set<Long> captions = two ? captionCells(raw) : Set.of();
        if (dimension2) {
            for (Map.Entry<Integer, TreeMap<Integer, RawSheet.Cell>> row : raw.rows.tailMap(bodyStart).entrySet()) {
                for (Map.Entry<Integer, RawSheet.Cell> cell : row.getValue().entrySet()) {
                    if (covered.contains(key(row.getKey(), cell.getKey()))
                            || captions.contains(key(row.getKey(), cell.getKey()))) {
                        continue;
                    }
                    RawSheet.Cell found = cell.getValue();
                    String id = rowIds.get(row.getKey() - bodyStart) + ":" + columns.get(cell.getKey()).id();
                    cells.put(id, new Cell(found.text(), two ? styleTwo(found) : style(found), null));
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
        SheetDocument document = null;
        if (two) {
            Context context = new Context(raw, bodyStart, maxRow, maxColumn, rowIds, columns, cells, dimension2);
            document = new SheetDocument(List.of(sheet(context, grid)));
        }
        List<RawSheet.Place> places = raw.embeddedAt.size() > MOST_PLACES ? raw.embeddedAt.subList(0, MOST_PLACES)
                : raw.embeddedAt;
        return new Built(grid, document, new Report(columns.size(), lines.size(), merges.size(), notes.entries(),
                labels, title, raw.worksheets, raw.worksheet, List.copyOf(places)));
    }

    /**
     * The cells that hold nothing but the words Yes or No under a check box (SH1B): the text is the caption of the box,
     * not a question, so the cell becomes the yes/no cell and the caption is left out. A cell with any other text is a
     * question and keeps it.
     */
    private static Set<Long> captionCells(RawSheet raw) {
        Set<Long> cells = new HashSet<>();
        for (RawSheet.YesNo box : raw.yesNo) {
            var row = raw.rows.get(box.row());
            RawSheet.Cell held = row == null ? null : row.get(box.column());
            if (held != null) {
                String bare = held.text().toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z]", "");
                if (bare.equals("yes") || bare.equals("no") || bare.equals("yesno") || bare.equals("noyes")) {
                    cells.add(key(box.row(), box.column()));
                }
            }
        }
        return cells;
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

    // ---- the columns and the heights ----

    private List<Column> columns(RawSheet raw, boolean labels, int minRow, int count, boolean two) {
        List<Column> columns = new ArrayList<>();
        Integer[] widths = new Integer[count];
        if (two) {
            for (RawSheet.ColumnSpec spec : raw.columnSpecs) {
                if (!spec.custom() || spec.hidden()) {
                    continue;
                }
                int pixels = Math.max(limits.minColumnWidth(), Math.min(limits.maxColumnWidth(),
                        (int) Math.round(spec.chars() * 7)));
                for (int c = spec.first(); c <= Math.min(spec.last(), count - 1); c++) {
                    widths[c] = pixels;
                }
            }
        } else {
            for (RawSheet.Width width : raw.widths) {
                for (int c = width.first(); c <= Math.min(width.last(), count - 1); c++) {
                    widths[c] = width.pixels();
                }
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

    private Integer height(RawSheet raw, int row, boolean two) {
        if (!two) {
            return raw.heights.get(row);
        }
        RawSheet.RowSpec spec = raw.rowSpecs.get(row);
        if (spec == null || !spec.custom() || spec.hidden()) {
            return null;
        }
        return Math.max(limits.minRowHeight(), Math.min(limits.maxRowHeight(), (int) Math.round(spec.points() * 4
                / 3)));
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
            for (Map.Entry<Integer, TreeMap<Integer, RawSheet.Cell>> row
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
    private void checkTexts(RawSheet raw, boolean labels, int minRow, int bodyStart, boolean dimension2,
            int fixedMost) {
        int problems = 0;
        ImportRefusal first = null;
        for (Map.Entry<Integer, TreeMap<Integer, RawSheet.Cell>> row : raw.rows.entrySet()) {
            boolean label = labels && row.getKey() == minRow;
            if (!label && (!dimension2 || row.getKey() < bodyStart)) {
                continue;
            }
            int most = label ? limits.maxLabelChars() : fixedMost;
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

    // ---- format version 2 ----

    /** What the version 2 part of the build needs to know about the grid that was made. */
    private record Context(RawSheet raw, int bodyStart, int maxRow, int maxColumn, List<String> rowIds,
            List<Column> columns, Map<String, Cell> cells, boolean named) {

        String key(int row, int column) {
            return rowIds.get(row - bodyStart) + ":" + columns.get(column).id();
        }

        boolean inside(int row, int column) {
            return named && row >= bodyStart && row <= maxRow && column >= 0 && column <= maxColumn;
        }
    }

    /**
     * The style a version 2 cell keeps: bold, italic, underline and alignment. The size, the colour and everything
     * else go in the look, so a cell never states the same thing twice.
     */
    private static Style styleTwo(RawSheet.Cell cell) {
        StyleBook.Format format = cell.format();
        if (format == null) {
            return style(cell);
        }
        Alignment alignment = format.horizontal() == null ? null : Alignment.valueOf(format.horizontal());
        if (!format.bold() && !format.italic() && !format.underline() && alignment == null) {
            return null;
        }
        return new Style(format.bold(), format.italic(), null, format.underline(), alignment, null);
    }

    private Tab sheet(Context context, SheetGrid grid) {
        RawSheet raw = context.raw();
        ImportNote.Counts notes = raw.notes;
        Map<String, SheetLook> looks = new LinkedHashMap<>();
        Map<String, String> links = new LinkedHashMap<>();
        Map<String, Input> inputs = new LinkedHashMap<>();
        List<Condition> conditions = new ArrayList<>();
        if (context.named()) {
            looks(context, looks);
            links(context, links);
            inputs(context, inputs);
            conditions(context, conditions);
        } else {
            notes.add(ImportNote.CONDITIONS_LEFT_OUT, raw.rules.size());
            notes.add(ImportNote.LINKS_NOT_KEPT, raw.links.size());
            notes.add(ImportNote.BOXES_NOT_PLACED, boxesIn(raw.yesNo) + raw.unplacedBoxes.size());
        }
        Freeze freeze = freeze(context, grid);
        Print print = raw.print == null ? null : new Print(raw.print.paper(), raw.print.orientation(),
                raw.print.fitToWidth(), raw.print.repeatHeaderRows());
        return new Tab(SHEET_KEY, raw.title, true, freeze, print, inputs, grid, looks, links, conditions);
    }

    private static int boxesIn(List<RawSheet.YesNo> cells) {
        int count = 0;
        for (RawSheet.YesNo cell : cells) {
            count += cell.boxes();
        }
        return count;
    }

    /** The look of every cell that has one: cells with text, and blank cells that show a fill or a border. */
    private static void looks(Context context, Map<String, SheetLook> looks) {
        RawSheet raw = context.raw();
        for (Map.Entry<Integer, TreeMap<Integer,
                RawSheet.Cell>> row : raw.rows.tailMap(context.bodyStart()).entrySet()) {
            for (Map.Entry<Integer, RawSheet.Cell> cell : row.getValue().entrySet()) {
                StyleBook.Format format = cell.getValue().format();
                if (format != null && format.look() != null && context.inside(row.getKey(), cell.getKey())) {
                    looks.put(context.key(row.getKey(), cell.getKey()), format.look());
                }
            }
        }
        for (Map.Entry<Integer, TreeMap<Integer, StyleBook.Format>> row : raw.blankLooks
                .tailMap(context.bodyStart()).entrySet()) {
            for (Map.Entry<Integer, StyleBook.Format> cell : row.getValue().entrySet()) {
                StyleBook.Format format = cell.getValue();
                if (format.look() != null && context.inside(row.getKey(), cell.getKey())) {
                    looks.putIfAbsent(context.key(row.getKey(), cell.getKey()), format.look());
                }
            }
        }
    }

    /** A link stays on a cell that holds fixed text; any other link is counted and left out. */
    private void links(Context context, Map<String, String> links) {
        RawSheet raw = context.raw();
        for (Map.Entry<Long, String> link : raw.links.entrySet()) {
            int row = (int) (link.getKey() >> 32);
            int column = (int) (link.getKey() & 0xffffffffL);
            String cellKey = context.inside(row, column) ? context.key(row, column) : null;
            Cell cell = cellKey == null ? null : context.cells().get(cellKey);
            if (cell != null && cell.value() instanceof String text && !text.isEmpty()
                    && link.getValue().length() <= limits.maxLinkChars()) {
                links.put(cellKey, link.getValue());
            } else {
                raw.notes.add(ImportNote.LINKS_NOT_KEPT);
            }
        }
    }

    /** A check box makes a blank cell a yes/no cell; a box on a cell that holds text is reported, never merged. */
    private static void inputs(Context context, Map<String, Input> inputs) {
        RawSheet raw = context.raw();
        int boxes = 0;
        int pairs = 0;
        int beside = 0;
        int notPlaced = raw.unplacedBoxes.size();
        List<RawSheet.YesNo> onText = new ArrayList<>();
        Set<Long> boxCells = new HashSet<>();
        for (RawSheet.YesNo cell : raw.yesNo) {
            boxCells.add(key(cell.row(), cell.column()));
        }
        for (RawSheet.YesNo cell : raw.yesNo) {
            String cellKey = context.inside(cell.row(), cell.column()) ? context.key(cell.row(), cell.column()) : null;
            Cell found = cellKey == null ? null : context.cells().get(cellKey);
            boolean covered = cellKey != null && coveredBy(raw, cell.row(), cell.column());
            if (cellKey != null && !covered && found != null && found.value() != null) {
                onText.add(cell);
            } else if (cellKey == null || covered) {
                notPlaced += cell.boxes();
            } else {
                inputs.put(cellKey, new Input("boolean", false, InputClass.FILLER, null, null));
                boxes += cell.boxes();
                if (cell.boxes() == 2) {
                    pairs++;
                }
            }
        }
        // A box over a question: the question stays, and the cell just after it (and after its merged area) becomes
        // the yes/no cell when that cell is inside the table, empty, free and not itself under a box.
        for (RawSheet.YesNo cell : onText) {
            int column = rightEdge(raw, cell.row(), cell.column()) + 1;
            String target = context.inside(cell.row(), column) ? context.key(cell.row(), column) : null;
            if (target != null && !coveredBy(raw, cell.row(), column) && context.cells().get(target) == null
                    && !inputs.containsKey(target) && !boxCells.contains(key(cell.row(), column))) {
                inputs.put(target, new Input("boolean", false, InputClass.FILLER, null, null));
                beside += cell.boxes();
            } else {
                notPlaced += cell.boxes();
            }
        }
        raw.notes.add(ImportNote.CHECK_BOXES, boxes);
        raw.notes.add(ImportNote.YES_NO_PAIRS, pairs);
        raw.notes.add(ImportNote.BOXES_BESIDE, beside);
        raw.notes.add(ImportNote.BOXES_NOT_PLACED, notPlaced);
    }

    /** The last column of the merged area that starts at the position, else the column itself. */
    private static int rightEdge(RawSheet raw, int row, int column) {
        for (RawSheet.Area area : raw.merges) {
            if (area.top() == row && area.left() == column) {
                return area.right();
            }
        }
        return column;
    }

    /** Whether the position is a cell under a merged area, that is not its top left cell. */
    private static boolean coveredBy(RawSheet raw, int row, int column) {
        for (RawSheet.Area area : raw.merges) {
            if (row >= area.top() && row <= area.bottom() && column >= area.left() && column <= area.right()
                    && (row != area.top() || column != area.left())) {
                return true;
            }
        }
        return false;
    }

    /** The conditional colours, cut to the part of the sheet that was kept; one that lies outside is counted. */
    private static void conditions(Context context, List<Condition> conditions) {
        RawSheet raw = context.raw();
        for (RawSheet.Rule rule : raw.rules) {
            int top = Math.max(rule.top(), context.bodyStart());
            int bottom = Math.min(rule.bottom(), context.maxRow());
            int left = Math.max(rule.left(), 0);
            int right = Math.min(rule.right(), context.maxColumn());
            if (top > bottom || left > right || conditions.size() >= MOST_CONDITIONS) {
                raw.notes.add(ImportNote.CONDITIONS_LEFT_OUT);
                continue;
            }
            conditions.add(new Condition(context.key(top, left), context.key(bottom, right), rule.operator(),
                    rule.first(), rule.second(), rule.fill(), rule.colour()));
        }
    }

    /** The frozen panes: the rows below the labels and the columns, never more than the sheet or the format holds. */
    private static Freeze freeze(Context context, SheetGrid grid) {
        RawSheet raw = context.raw();
        int rows = context.named() ? Math.min(MOST_FREEZE_ROWS, Math.min(grid.rows().size(),
                Math.max(0, raw.freezeRows - context.bodyStart()))) : 0;
        int columns = Math.min(MOST_FREEZE_COLUMNS, Math.min(grid.columns().size(), raw.freezeColumns));
        return rows == 0 && columns == 0 ? null : new Freeze(rows, columns);
    }
}
