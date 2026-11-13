package app.platform.sheets.internal;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * What a reader found in a file before it is a grid: the cells that hold text, by position, with the sizes and merged
 * areas the file states (UI3B, ADR-0195). Positions are zero based (row, column) as in the file. A library's format
 * ends here: nothing of it is in this type. Everything in it is text of the organization, so it prints as a marker.
 */
final class RawSheet {

    /**
     * One cell that holds text.
     *
     * @param text the text, never empty
     * @param bold whether the file shows it bold
     * @param italic whether the file shows it italic
     * @param points the font size in points, or 0 when the file does not say
     * @param format everything else the file says about how the cell looks, or null when the reader did not read it
     *        (a text file has none)
     */
    record Cell(String text, boolean bold, boolean italic, int points, StyleBook.Format format) {

        Cell(String text, boolean bold, boolean italic, int points) {
            this(text, bold, italic, points, null);
        }

        @Override
        public String toString() {
            return "Cell[redacted]";
        }
    }

    /** A merged area between two cells, both included. */
    record Area(int top, int left, int bottom, int right) {
    }

    /** A width stated for the columns {@code first} to {@code last}, in pixels. */
    record Width(int first, int last, int pixels) {
    }

    /**
     * A column definition as the file states it, for the sheet of format version 2 and for placing drawn objects.
     *
     * @param first the first column
     * @param last the last column
     * @param chars the width in characters of the default digit
     * @param hidden whether the columns are hidden
     * @param custom whether the width was set by hand (otherwise it is the default the program chose)
     */
    record ColumnSpec(int first, int last, double chars, boolean hidden, boolean custom) {
    }

    /**
     * A row as the file states it.
     *
     * @param points the height in points
     * @param hidden whether the row is hidden
     * @param custom whether the height was set by hand
     */
    record RowSpec(double points, boolean hidden, boolean custom) {
    }

    /**
     * A conditional colour that compares the value of a cell with a number. Positions are zero based and both ends
     * are included.
     *
     * @param operator LT, LTE, EQ, NEQ, GTE, GT, BETWEEN or NOT_BETWEEN
     * @param first the number compared with (a plain decimal text)
     * @param second the second number of BETWEEN and NOT_BETWEEN, otherwise null
     * @param fill the background to paint as {@code #rrggbb}, or null
     * @param colour the text colour to paint as {@code #rrggbb}, or null
     */
    record Rule(int top, int left, int bottom, int right, String operator, String first, String second, String fill,
            String colour) {
    }

    /**
     * The page set-up the file states.
     *
     * @param paper A4, A3 or LETTER
     * @param orientation PORTRAIT or LANDSCAPE
     * @param fitToWidth whether the sheet is scaled to the width of the page
     * @param repeatHeaderRows whether rows at the top are repeated on every page
     */
    record Print(String paper, String orientation, boolean fitToWidth, boolean repeatHeaderRows) {
    }

    /**
     * A visible worksheet of the workbook.
     *
     * @param index the number among the visible worksheets, from zero
     * @param title the name of the tab, or null when it cannot be used as a title (markup or too long)
     * @param rows the rows the file says the worksheet covers (declared, for choosing)
     * @param columns the columns the file says it covers (declared, for choosing)
     */
    record Worksheet(int index, String title, int rows, int columns) {

        @Override
        public String toString() {
            return "Worksheet[redacted]";
        }
    }

    /** A position in the file, zero based. */
    record Place(int row, int column) {
    }

    /**
     * A cell that becomes a yes/no cell because of check boxes.
     *
     * @param row the row, zero based
     * @param column the column, zero based
     * @param boxes the check boxes that made it: 2 for a Yes box with its No box, otherwise 1
     */
    record YesNo(int row, int column, int boxes) {
    }

    /**
     * A formula as the file holds it (SH2).
     *
     * @param text the formula in spreadsheet text without the equals sign, with a shared formula already given to
     *        every cell of its group; null when the formula cannot be used (see {@code reason})
     * @param reason why there is no text: {@code array} for an array formula or a data table, {@code shared} for a
     *        member of a shared group whose first cell was not met or whose text cannot move, {@code empty} for a
     *        formula element with no text; null when there is a text
     */
    record Formula(String text, String reason) {

        @Override
        public String toString() {
            return "Formula[redacted]";
        }
    }

    private static final java.util.regex.Pattern FOOTER = java.util.regex.Pattern.compile(
            "Rev No\\.'[0-9]{2,}'$");

    /**
     * The part of the sheet the table covers (FA14, ADR-0250), zero based, both ends included.
     *
     * @param top the first row of the table
     * @param bottom the last row of the table
     * @param right the last column of the table
     */
    record Extent(int top, int bottom, int right) {
    }

    final TreeMap<Integer, TreeMap<Integer, Cell>> rows = new TreeMap<>();
    /** The cells that have a border and no text (FA14): the columns of a row are bits, so a row costs a few bytes. */
    final TreeMap<Integer, BitSet> bordered = new TreeMap<>();
    final Map<Integer, Integer> heights = new HashMap<>();
    final List<Width> widths = new ArrayList<>();
    final List<Area> merges = new ArrayList<>();
    final ImportNote.Counts notes = new ImportNote.Counts();

    // ---- what the reader of format version 2 adds (SH1B) ----

    /** The cells with no text that show something of themselves (a fill, a border), by position. */
    final TreeMap<Integer, TreeMap<Integer, StyleBook.Format>> blankLooks = new TreeMap<>();
    final List<ColumnSpec> columnSpecs = new ArrayList<>();
    final TreeMap<Integer, RowSpec> rowSpecs = new TreeMap<>();
    double defaultColumnChars = 9.140625;
    double defaultRowPoints = 15;
    /** Rows and columns frozen, counted from the top left of the worksheet as the file counts them. */
    int freezeRows;
    int freezeColumns;
    Print print;
    final List<Rule> rules = new ArrayList<>();
    /** The formulas of the worksheet by position (SH2): the cell may or may not also hold its last value as text. */
    final TreeMap<Integer, TreeMap<Integer, Formula>> formulas = new TreeMap<>();
    /**
     * The value of every cell the file gives one, by position, as the file stores it (SH2): a Double, a String, a
     * Boolean or an XlError. For a formula this is the last value the spreadsheet program calculated. It is kept for
     * the check of the platform's own calculation against the program's, never stored in a sheet.
     */
    final TreeMap<Integer, TreeMap<Integer, Object>> typed = new TreeMap<>();
    /** The https address of the cell at a position (row shifted left by 32 bits, then column). */
    final Map<Long, String> links = new HashMap<>();
    /**
     * The longest formula and the deepest nesting met in the worksheet, so a person can see the margin to the limits.
     */
    int longestFormula;
    int deepestFormula;
    /** How many check boxes were placed in a cell, and how many of them were a Yes box and a No box in a pair. */
    int boxesPlaced;
    int boxPairs;
    /** The cells that become yes/no cells from check boxes. */
    final List<YesNo> yesNo = new ArrayList<>();
    /** The cells where an embedded file stood. */
    final List<Place> embeddedAt = new ArrayList<>();
    /** The check boxes that could not be placed in a cell. */
    final List<Place> unplacedBoxes = new ArrayList<>();
    /** The visible worksheets of the workbook; empty for a text file. */
    List<Worksheet> worksheets = List.of();
    /** The worksheet this was read from, among the visible ones, or -1 for a text file. */
    int worksheet = -1;
    /** The name of the tab the sheet was read from, or null when it cannot be used. */
    String title;

    void put(int row, int column, Cell cell) {
        rows.computeIfAbsent(row, key -> new TreeMap<>()).put(column, cell);
    }

    /** Keeps how a cell with no text looks when it shows something (a fill or a border). */
    void look(int row, int column, StyleBook.Format format) {
        if (format != null && format.visible()) {
            blankLooks.computeIfAbsent(row, key -> new TreeMap<>()).put(column, format);
        }
    }

    /** Notes that a cell with a border holds no text; it is part of the table all the same. */
    void border(int row, int column) {
        bordered.computeIfAbsent(row, key -> new BitSet()).set(column);
    }

    /** Whether a cell with a border and no text lies in the area. */
    boolean borderedIn(Area area) {
        for (BitSet columns : bordered.subMap(area.top(), true, area.bottom(), true).values()) {
            int first = columns.nextSetBit(area.left());
            if (first >= 0 && first <= area.right()) {
                return true;
            }
        }
        return false;
    }

    /**
     * The part of the sheet the table covers (FA14, ADR-0250): every cell that has text or a border, and the whole of
     * a merged area that holds a cell with a border. A blank cell with no border is not part of it, so the empty
     * area around a table does not make the grid larger. Call it only when {@link #isEmpty()} is false.
     */
    Extent extent() {
        return extent(false);
    }

    /**
     * The same, and, when asked, the cells that hold a formula count as cells of the table even when their last value
     * is empty (SH2: a calculated column's formula shows an empty text on a blank row).
     */
    Extent extent(boolean withFormulas) {
        Extent base = extentOfText();
        if (!withFormulas || formulas.isEmpty()) {
            return base;
        }
        int top = Math.min(base.top(), formulas.firstKey());
        int bottom = Math.max(base.bottom(), formulas.lastKey());
        int right = base.right();
        for (NavigableMap<Integer, Formula> row : formulas.values()) {
            right = Math.max(right, row.lastKey());
        }
        return new Extent(top, bottom, right);
    }

    private Extent extentOfText() {
        int top = rows.isEmpty() ? Integer.MAX_VALUE : rows.firstKey();
        int bottom = rows.isEmpty() ? 0 : rows.lastKey();
        int right = 0;
        for (NavigableMap<Integer, Cell> row : rows.values()) {
            right = Math.max(right, row.lastKey());
        }
        if (!bordered.isEmpty()) {
            top = Math.min(top, bordered.firstKey());
            bottom = Math.max(bottom, bordered.lastKey());
            for (BitSet columns : bordered.values()) {
                right = Math.max(right, columns.length() - 1);
            }
        }
        for (Area area : merges) {
            if (borderedIn(area)) {
                top = Math.min(top, area.top());
                bottom = Math.max(bottom, area.bottom());
                right = Math.max(right, area.right());
            }
        }
        return new Extent(top, bottom, right);
    }

    /**
     * The footer row the platform adds when it shows or downloads a sheet (FA7, FA8): a single text at the right of the
     * last row that ends with {@code Rev No.} and a number in single quotes. It is not part of the sheet, so a file
     * that
     * was downloaded and sent back does not bring it in as a row; it is left out and counted.
     */
    void dropFooter() {
        if (rows.isEmpty()) {
            return;
        }
        int last = rows.lastKey();
        TreeMap<Integer, Cell> cells = rows.get(last);
        if (cells.size() != 1 || !bordered.isEmpty() && bordered.lastKey() > last) {
            return;
        }
        String text = cells.firstEntry().getValue().text();
        if (text != null && FOOTER.matcher(text.strip()).find()) {
            rows.remove(last);
            bordered.remove(last);
            blankLooks.remove(last);
            heights.remove(last);
            notes.add(ImportNote.FOOTER_ROW);
        }
    }

    boolean isEmpty() {
        return rows.isEmpty();
    }

    /** Whether the worksheet holds neither text nor a formula (SH2: a sheet of formulas alone is not empty). */
    boolean holdsNothing() {
        return rows.isEmpty() && formulas.isEmpty();
    }

    @Override
    public String toString() {
        return "RawSheet[redacted]";
    }
}
