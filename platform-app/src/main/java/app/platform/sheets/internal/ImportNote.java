package app.platform.sheets.internal;

import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The things a file holds that the sheet does not, or holds in another form, and the plain words for each (UI3B,
 * ADR-0195). Only a count is kept: what was left out is never described by its content.
 */
enum ImportNote {

    IMAGES(true, "image", "images", "left out"),
    VECTOR_PICTURES(true, "picture in a vector format", "pictures in a vector format",
            "left out (a picture of that kind is never opened)"),
    CHARTS(true, "chart", "charts", "left out"),
    OTHER_SHEETS(true, "other worksheet", "other worksheets",
            "left out (a template holds one sheet: choose the worksheet, and upload each of the others into its own "
                    + "template)"),
    HIDDEN_SHEETS(true, "hidden worksheet", "hidden worksheets", "skipped"),
    EXTERNAL_LINKS(true, "link to another file", "links to other files", "left out (nothing is ever fetched)"),
    PIVOTS(true, "pivot table", "pivot tables", "left out"),
    COMMENT_PARTS(true, "worksheet with comments", "worksheets with comments", "read without the comments"),
    DEFINED_NAMES(true, "defined name", "defined names", "left out"),
    EMBEDDED_FILES(true, "embedded file", "embedded files",
            "left out (the cells where they stood are listed below)"),
    SHAPES(true, "drawing shape", "drawing shapes", "left out"),
    FORM_CONTROLS(true, "form control that is not a check box", "form controls that are not check boxes",
            "left out"),
    LINKS_INSIDE(true, "link inside the workbook", "links inside the workbook", "not kept"),
    LINKS_NOT_KEPT(true, "link that is not a secure web address", "links that are not secure web addresses",
            "not kept (only https addresses are kept)"),
    CONDITIONS_LEFT_OUT(true, "conditional colour", "conditional colours",
            "left out (only a comparison of a cell value with a number is kept)"),
    BOXES_NOT_PLACED(true, "check box", "check boxes",
            "left out (it stood on a cell that holds a question or other text, which is kept, or outside the table)"),
    ERROR_CELLS(true, "cell that held an error", "cells that held an error", "left empty"),
    MERGES_LEFT_OUT(true, "merged area", "merged areas",
            "left out (outside the part that was read, or touching the first row, or overlapping another)"),
    COVERED_CELLS(true, "cell under a merged area", "cells under a merged area",
            "left empty (a merged area keeps the text of its top left cell only)"),
    ROWS_NOT_KEPT(true, "row", "rows",
            "left out (in this sheet the person who fills it in adds the rows)"),
    FOOTER_ROW(true, "footer row", "footer rows",
            "left out (the platform adds it when it shows or downloads a sheet)"),
    CHECK_BOXES(false, "check box", "check boxes", "turned into yes/no cells"),
    BOXES_BESIDE(false, "check box", "check boxes",
            "given a yes/no cell beside the question, which is kept (please check each one)"),
    YES_NO_PAIRS(false, "pair of a Yes box and a No box", "pairs of a Yes box and a No box",
            "turned into one yes/no cell each"),
    NUMBER_FORMAT_UNKNOWN(false, "cell with a number format the sheet does not have", "cells with a number format "
            + "the sheet does not have", "kept as plain numbers"),
    FONT_FALLBACK(false, "font the sheet does not have", "fonts the sheet does not have",
            "drawn in a similar standard font"),
    FORMULAS(false, "formula", "formulas", "read as its last value"),
    DATES(false, "date", "dates", "written year-month-day"),
    BORDERED_BLANKS(false, "blank cell with a border", "blank cells with a border",
            "kept as cells to be filled in (a blank cell with no border is not part of the table)"),
    TITLE_ROW(false, "title row", "title rows",
            "found (it is kept as the first row of the sheet and does not name a column)");

    private final boolean leftOut;
    private final String one;
    private final String many;
    private final String what;

    ImportNote(boolean leftOut, String one, String many, String what) {
        this.leftOut = leftOut;
        this.one = one;
        this.many = many;
        this.what = what;
    }

    /** Whether this is something that did not come in (otherwise it came in in another form). */
    boolean leftOut() {
        return leftOut;
    }

    String code() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The sentence for a count of at least one. */
    String words(int count) {
        return count + " " + (count == 1 ? one : many) + (count == 1 ? " was " : " were ") + what + ".";
    }

    /** A running count of each note. */
    static final class Counts {

        private final Map<ImportNote, Integer> counts = new EnumMap<>(ImportNote.class);

        void add(ImportNote note, int amount) {
            if (amount > 0) {
                counts.merge(note, amount, Integer::sum);
            }
        }

        void add(ImportNote note) {
            add(note, 1);
        }

        int of(ImportNote note) {
            return counts.getOrDefault(note, 0);
        }

        /** The notes with a count, in the order of the enumeration. */
        List<Entry> entries() {
            return counts.entrySet().stream().map(entry -> new Entry(entry.getKey(), entry.getValue())).toList();
        }
    }

    /**
     * One note with its count.
     *
     * @param note which
     * @param count how many, at least one
     */
    record Entry(ImportNote note, int count) {
    }
}
