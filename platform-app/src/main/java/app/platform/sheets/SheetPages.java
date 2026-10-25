package app.platform.sheets;

import java.util.List;
import java.util.Map;

/**
 * A page of positioned cells, for a module that has a form of its own to show as a spreadsheet (FA8, ADR-0202): drawn
 * on the screen as the sheet grid, written as an Excel file, and read back from one. The sheets module knows no
 * business word: the module decides which cell holds what; this decides how a page looks and what a file may hold.
 *
 * <p>Every cell is text. A text that starts like a formula is written as plain text with the quote prefix, never as a
 * formula; no macro, link or image is written, and the size of a file is bounded. A file that is read goes through the
 * limits of the spreadsheet upload, and nothing of it is kept. It asks nobody and reads no table. It is reachable from
 * the cluster only (an architecture test names it).
 */
public interface SheetPages {

    /**
     * One cell of a page.
     *
     * @param row the row, from 1
     * @param column the column, from 1
     * @param text what it says; empty for a bordered blank
     * @param bold whether it is bold
     * @param large whether it is written larger (a title)
     * @param center whether it is centred
     */
    record Cell(int row, int column, String text, boolean bold, boolean large, boolean center) {

        /** Prints a marker only: the text is the organization's. */
        @Override
        public String toString() {
            return "Cell[redacted]";
        }
    }

    /**
     * A merged area, rows and columns from 1, ends included.
     *
     * @param top its first row
     * @param left its first column
     * @param bottom its last row
     * @param right its last column
     */
    record Area(int top, int left, int bottom, int right) {
    }

    /**
     * A page.
     *
     * @param columnWidths the width of each column in pixels
     * @param rowHeights the height of each row in pixels
     * @param cells the cells; a cell not listed is a bordered blank
     * @param merges the merged areas
     */
    record Page(List<Integer> columnWidths, List<Integer> rowHeights, List<Cell> cells, List<Area> merges) {

        /** Copies the lists. */
        public Page {
            columnWidths = List.copyOf(columnWidths);
            rowHeights = List.copyOf(rowHeights);
            cells = List.copyOf(cells);
            merges = List.copyOf(merges);
        }
    }

    /**
     * What a cell of a file said.
     *
     * @param row the row, from 1
     * @param column the column, from 1
     * @param text the text of the cell
     */
    record Found(int row, int column, String text) {

        /** Prints a marker only. */
        @Override
        public String toString() {
            return "Found[redacted]";
        }
    }

    /** The page as an .xlsx workbook with no label band above it and no footer below it. */
    byte[] xlsx(Page page);

    /** The page as the sheet document the screen's grid draws, in the format of a sheet version. */
    Map<String, Object> grid(Page page);

    /**
     * The non-empty cells of the first sheet of a workbook.
     *
     * @param file the bytes of an .xlsx workbook
     * @return the cells, in reading order
     * @throws app.platformapi.ApiException {@code VALIDATION_ERROR} (not a workbook, a limit passed, macros),
     *         {@code PAYLOAD_TOO_LARGE}, {@code RATE_LIMITED}
     */
    List<Found> read(byte[] file);
}
