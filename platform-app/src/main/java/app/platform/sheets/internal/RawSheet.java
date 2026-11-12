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
     */
    record Cell(String text, boolean bold, boolean italic, int points) {

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

    void put(int row, int column, Cell cell) {
        rows.computeIfAbsent(row, key -> new TreeMap<>()).put(column, cell);
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
        int top = rows.firstKey();
        int bottom = rows.lastKey();
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
            heights.remove(last);
            notes.add(ImportNote.FOOTER_ROW);
        }
    }

    boolean isEmpty() {
        return rows.isEmpty();
    }

    @Override
    public String toString() {
        return "RawSheet[redacted]";
    }
}
