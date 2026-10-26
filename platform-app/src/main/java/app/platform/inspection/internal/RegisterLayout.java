package app.platform.inspection.internal;

import app.platform.sheets.SheetPages.Area;
import app.platform.sheets.SheetPages.Cell;
import app.platform.sheets.SheetPages.Found;
import app.platform.sheets.SheetPages.Page;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Where every part of the Document Change Register sits on its page (FA8, ADR-0202): the fixed layout of the form the
 * business keeps, two columns and fourteen rows, with its merged areas and the words of its labels. A pure rule with no
 * database, so it is tested alone, in both directions: {@link #page} draws the values on the page, and {@link #parse}
 * takes the parts a requester types back out of a filled file.
 *
 * <p>Each box holds its label and, after it, the value, as on the paper form. An upload is read cell by cell at the
 * places below and nowhere else: text in any other place is refused in plain words that name the place and never the
 * text. The parts the platform writes (the document, the revision, who and when) and the parts the approvers complete
 * are not taken from a file, whatever it says in them. A value that starts like a formula is plain text here; the file
 * writer marks it so.
 */
final class RegisterLayout {

    /** The columns of the page. */
    static final int COLUMNS = 2;
    /** The rows of the page. */
    static final int ROWS = 14;

    private static final List<Integer> COLUMN_WIDTHS = List.of(420, 290);
    private static final List<Integer> ROW_HEIGHTS = List.of(49, 34, 34, 44, 135, 46, 159, 96, 42, 42, 42, 42, 48, 46);
    private static final List<Area> MERGES = List.of(new Area(1, 1, 1, 2), new Area(2, 1, 3, 1),
            new Area(5, 1, 5, 2), new Area(6, 1, 6, 2), new Area(7, 1, 7, 2), new Area(8, 1, 8, 2));

    private static final String TITLE = "Document Change Register";
    private static final String SERVER_LABEL = "Is revised document updated on server or issued in hard copy?  Yes/No";
    private static final String MASTER_LABEL = "Is master list updated with respect to latest revision? Yes/No";
    private static final String SERVER_YES = "Yes,revised document updated on common server";
    private static final String SERVER_NO = "No,revised document issued in hard copy";
    private static final String MASTER_YES = "Yes,master list updated w.r.t. latest revision";
    private static final String MASTER_NO = "No,master list not updated w.r.t. latest revision";
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE;

    /** The parts of the form that have a label and a value. */
    enum Part {
        DOCUMENT_NAME(2, 1, "Document Name :", false, false),
        REQUEST_NO(2, 2, "Request No:-", false, true),
        REQUESTED_DATE(3, 2, "Requested Date:-", false, false),
        DOCUMENT_NO(4, 1, "Document No. :", false, false),
        REV_NO(4, 2, "Rev. No:", false, false),
        CHANGE_REQUESTED(5, 1, "Change Requested -", true, true),
        REQUESTED_BY(6, 1, "Requested by :", false, false),
        BASIS_FOR_CHANGE(7, 1, "Basis for Change :", true, true),
        HORIZONTAL_DEPLOYMENT(8, 1, "Horizontal Deployment (If applicable) :", true, true),
        APPROVED_BY(9, 1, "Approved By:", false, false),
        SIGNATURE(9, 2, "Signature", false, false),
        NEW_REV_NO(10, 1, "New Rev. No:", false, false),
        EFFECTIVE_DATE(10, 2, "Effective Date :", false, false),
        NEW_REVISION_DATE(11, 1, "New Revision date:", false, false),
        DEPARTMENT(11, 2, "Department:", false, false),
        OBSOLETED_FORMAT(12, 1, "Format /Document obsoleted (if applicable)", false, true),
        OBSOLETED_DATE(12, 2, "Effective Date :", false, true);

        private final int row;
        private final int column;
        private final String label;
        private final boolean lines;
        private final boolean typed;

        Part(int row, int column, String label, boolean lines, boolean typed) {
            this.row = row;
            this.column = column;
            this.label = label;
            this.lines = lines;
            this.typed = typed;
        }

        /** Whether the person who raises the request types this part (a file may fill it). */
        boolean typed() {
            return typed;
        }
    }

    private RegisterLayout() {
    }

    /** The register drawn on its page with the values it has; a blank part shows its label alone. */
    static Page page(RegisterValues values) {
        Map<Part, String> shown = shown(values);
        List<Cell> cells = new ArrayList<>();
        cells.add(new Cell(1, 1, TITLE, true, true, true));
        for (Part part : Part.values()) {
            String value = shown.get(part);
            String text = value == null || value.isBlank() ? part.label
                    : part.label + (part.lines ? "\n" : " ") + value;
            cells.add(new Cell(part.row, part.column, text, true, false, false));
        }
        cells.add(new Cell(13, 1, SERVER_LABEL, true, false, false));
        cells.add(new Cell(13, 2, answer(values.serverUpdated(), SERVER_YES, SERVER_NO), false, false, false));
        cells.add(new Cell(14, 1, MASTER_LABEL, true, false, false));
        cells.add(new Cell(14, 2, answer(values.masterListUpdated(), MASTER_YES, MASTER_NO), false, false, false));
        return new Page(COLUMN_WIDTHS, ROW_HEIGHTS, cells, MERGES);
    }

    /** What each part says, ready to follow its label. */
    private static Map<Part, String> shown(RegisterValues v) {
        Map<Part, String> shown = new EnumMap<>(Part.class);
        shown.put(Part.DOCUMENT_NAME, v.documentName());
        shown.put(Part.REQUEST_NO, v.requestNo());
        shown.put(Part.REQUESTED_DATE, date(v.requestedOn()));
        shown.put(Part.DOCUMENT_NO, v.documentNo());
        shown.put(Part.REV_NO, v.revNo());
        shown.put(Part.CHANGE_REQUESTED, v.changeRequested());
        shown.put(Part.REQUESTED_BY, v.requestedBy());
        shown.put(Part.BASIS_FOR_CHANGE, v.basisForChange());
        shown.put(Part.HORIZONTAL_DEPLOYMENT, v.horizontalDeployment());
        shown.put(Part.APPROVED_BY, v.approvedBy());
        shown.put(Part.SIGNATURE, v.signatureNote());
        shown.put(Part.NEW_REV_NO, v.newRevNo());
        shown.put(Part.EFFECTIVE_DATE, date(v.effectiveOn()));
        shown.put(Part.NEW_REVISION_DATE, date(v.newRevisionOn()));
        shown.put(Part.DEPARTMENT, v.department());
        shown.put(Part.OBSOLETED_FORMAT, v.obsoletedFormat());
        shown.put(Part.OBSOLETED_DATE, date(v.obsoletedEffectiveOn()));
        return shown;
    }

    private static String answer(String stored, String yes, String no) {
        return "YES".equals(stored) ? yes : "NO".equals(stored) ? no : "";
    }

    private static String date(LocalDate day) {
        return day == null ? null : ISO.format(day);
    }

    /**
     * The parts a requester types, as the text of each (blank parts are absent).
     *
     * @param values the typed text by part
     * @param dateText the text of the obsoleted format's day, or null
     */
    record Typed(Map<Part, String> values, String dateText) {

        /** Prints a marker only. */
        @Override
        public String toString() {
            return "Typed[redacted]";
        }
    }

    /**
     * Takes the typed parts out of the cells of a filled file.
     *
     * @return the parts, or the plain words of the first reason the file cannot be the register
     */
    static Parsed parse(List<Found> cells) {
        Map<Long, String> byPlace = new java.util.HashMap<>();
        for (Found cell : cells) {
            if (cell.row() < 1 || cell.row() > ROWS || cell.column() < 1 || cell.column() > COLUMNS) {
                return Parsed.refused("The file has text outside the register (row " + cell.row() + ", column "
                        + SheetPagesColumns.letters(cell.column()) + "). Use the register you downloaded, in its "
                        + "first sheet.");
            }
            byPlace.put(key(cell.row(), cell.column()), cell.text());
        }
        Map<Part, String> values = new EnumMap<>(Part.class);
        String dateText = null;
        for (Part part : Part.values()) {
            if (!part.typed) {
                continue;
            }
            String text = byPlace.get(key(part.row, part.column));
            if (text == null) {
                continue;
            }
            String value = withoutLabel(text, part.label);
            if (value.isEmpty()) {
                continue;
            }
            if (part == Part.OBSOLETED_DATE) {
                dateText = value;
            } else {
                values.put(part, value);
            }
        }
        return new Parsed(new Typed(values, dateText), null);
    }

    /**
     * The outcome of reading a file.
     *
     * @param typed the typed parts, or null when the file was refused
     * @param refusal the plain words of the refusal, or null
     */
    record Parsed(Typed typed, String refusal) {

        static Parsed refused(String words) {
            return new Parsed(null, words);
        }
    }

    /** A day written as year-month-day, day-month-year or day/month/year. */
    static Optional<LocalDate> parseDate(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String clean = text.strip();
        for (DateTimeFormatter format : List.of(ISO, DateTimeFormatter.ofPattern("dd-MM-uuuu", Locale.ROOT),
                DateTimeFormatter.ofPattern("dd/MM/uuuu", Locale.ROOT))) {
            try {
                return Optional.of(LocalDate.parse(clean, format));
            } catch (DateTimeParseException tryNext) {
                continue;
            }
        }
        return Optional.empty();
    }

    /** The text after the label of its box, without the marks that separate them. */
    static String withoutLabel(String text, String label) {
        String clean = text.replace("\r\n", "\n").replace('\r', '\n').strip();
        String flat = clean.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        String wanted = label.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
        if (!flat.startsWith(wanted)) {
            return clean;
        }
        // Walk the original text past the label, character by character, treating runs of spaces as one.
        int taken = 0;
        int at = 0;
        boolean space = false;
        while (at < clean.length() && taken < wanted.length()) {
            char c = Character.toLowerCase(clean.charAt(at));
            if (Character.isWhitespace(c)) {
                if (!space) {
                    taken++;
                }
                space = true;
            } else {
                space = false;
                taken++;
            }
            at++;
        }
        String rest = clean.substring(at);
        int start = 0;
        while (start < rest.length() && (Character.isWhitespace(rest.charAt(start)) || rest.charAt(start) == ':'
                || rest.charAt(start) == '-')) {
            start++;
        }
        return rest.substring(start).strip();
    }

    private static long key(int row, int column) {
        return ((long) row << 8) | column;
    }

    /** Column letters, without reaching into the page implementation. */
    private static final class SheetPagesColumns {

        private SheetPagesColumns() {
        }

        static String letters(int column) {
            StringBuilder name = new StringBuilder();
            for (int n = column; n > 0; n = (n - 1) / 26) {
                name.insert(0, (char) ('A' + (n - 1) % 26));
            }
            return name.toString();
        }
    }
}
