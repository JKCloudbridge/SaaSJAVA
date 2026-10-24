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
    CHARTS(true, "chart", "charts", "left out"),
    OTHER_SHEETS(true, "other sheet", "other sheets", "left out (only the first sheet is read)"),
    EXTERNAL_LINKS(true, "link to another file", "links to other files", "left out (nothing is ever fetched)"),
    ERROR_CELLS(true, "cell that held an error", "cells that held an error", "left empty"),
    MERGES_LEFT_OUT(true, "merged area", "merged areas",
            "left out (outside the part that was read, or touching the first row, or overlapping another)"),
    COVERED_CELLS(true, "cell under a merged area", "cells under a merged area",
            "left empty (a merged area keeps the text of its top left cell only)"),
    ROWS_NOT_KEPT(true, "row", "rows",
            "left out (in this sheet the person who fills it in adds the rows)"),
    FORMULAS(false, "formula", "formulas", "read as its last value"),
    DATES(false, "date", "dates", "written year-month-day");

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
