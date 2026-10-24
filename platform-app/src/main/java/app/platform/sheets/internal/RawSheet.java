package app.platform.sheets.internal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    final TreeMap<Integer, TreeMap<Integer, Cell>> rows = new TreeMap<>();
    final Map<Integer, Integer> heights = new HashMap<>();
    final List<Width> widths = new ArrayList<>();
    final List<Area> merges = new ArrayList<>();
    final ImportNote.Counts notes = new ImportNote.Counts();

    void put(int row, int column, Cell cell) {
        rows.computeIfAbsent(row, key -> new TreeMap<>()).put(column, cell);
    }

    boolean isEmpty() {
        return rows.isEmpty();
    }

    @Override
    public String toString() {
        return "RawSheet[redacted]";
    }
}
