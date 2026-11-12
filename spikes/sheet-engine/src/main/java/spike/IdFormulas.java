package spike;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * WP4. The id-based canonical form of a formula and its two translators.
 *
 * <p>Stored form: the formula as a spreadsheet program writes it, except that every cell reference is replaced by a token that names the column
 * and the row by their PERMANENT IDENTIFIERS: {@code @{COL}} is that column on the formula's own row; {@code @{COL,ROW}} is a
 * cell of another row (or a named row); a third part holds the absolute markers, {@code c} for an absolute column and {@code r}
 * for an absolute row: {@code @{COL,ROW,cr}}, {@code @{COL,,c}}. A range is two tokens joined by a colon. Everything else
 * (functions, operators, numbers, text) is kept character for character, so the text round-trips exactly. Because identifiers do
 * not move when a row or column is inserted, the stored text is never rewritten by an insert; only the Excel-style text drawn
 * from it changes.
 */
public final class IdFormulas {

    /** The sheet's columns and rows in order, as identifiers. Position 0 is column A / row 1. */
    public record Layout(List<String> colIds, List<String> rowIds) {
        public Layout {
            colIds = List.copyOf(colIds);
            rowIds = List.copyOf(rowIds);
        }

        Layout withRowInserted(int index, String id) {
            List<String> r = new ArrayList<>(rowIds);
            r.add(index, id);
            return new Layout(colIds, r);
        }

        Layout withColumnInserted(int index, String id) {
            List<String> c = new ArrayList<>(colIds);
            c.add(index, id);
            return new Layout(c, rowIds);
        }
    }

    /** A formula that cannot be translated. The key is stable; it never repeats the formula. */
    public static final class Refused extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final String key;

        Refused(String key) {
            super(key, null, false, false);
            this.key = key;
        }

        public String key() {
            return key;
        }
    }

    private static final Pattern A1 = Pattern.compile("(\\$?)([A-Za-z]{1,3})(\\$?)([0-9]{1,7})");
    private static final Pattern ID_TOKEN = Pattern.compile("@\\{([A-Za-z0-9_-]{1,40})(?:,([A-Za-z0-9_-]{0,40}))?(?:,([cr]{0,2}))?}");

    private IdFormulas() {
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Excel-style -> ids

    /** @param row 1-based row of the cell that holds the formula; @param col 0-based column of that cell */
    public static String toIds(String excel, int row, Layout layout) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        int n = excel.length();
        while (i < n) {
            char ch = excel.charAt(i);
            if (ch == '"') {
                int end = endOfText(excel, i);
                out.append(excel, i, end);
                i = end;
                continue;
            }
            if (ch == '@' || ch == '{' || ch == '[' || ch == '!' || ch == '\'') {
                throw new Refused("character_not_allowed");
            }
            if (isWordStart(ch, excel, i)) {
                Matcher m = A1.matcher(excel).region(i, n);
                if (m.lookingAt()) {
                    int end = m.end();
                    boolean followedByWord = end < n && (Character.isLetterOrDigit(excel.charAt(end))
                            || excel.charAt(end) == '_' || excel.charAt(end) == '(' || excel.charAt(end) == '.');
                    if (!followedByWord) {
                        out.append(token(m, row, layout));
                        i = end;
                        continue;
                    }
                }
                int end = i;
                while (end < n && (Character.isLetterOrDigit(excel.charAt(end)) || excel.charAt(end) == '_'
                        || excel.charAt(end) == '.')) {
                    end++;
                }
                out.append(excel, i, end);
                i = Math.max(end, i + 1);
                continue;
            }
            out.append(ch);
            i++;
        }
        return out.toString();
    }

    private static String token(Matcher m, int cellRow, Layout layout) {
        boolean absCol = !m.group(1).isEmpty();
        boolean absRow = !m.group(3).isEmpty();
        int col = columnIndex(m.group(2).toUpperCase(java.util.Locale.ROOT));
        int rowNo = Integer.parseInt(m.group(4));
        if (col < 0 || col >= layout.colIds().size()) {
            throw new Refused("column_outside_sheet");
        }
        if (rowNo < 1 || rowNo > layout.rowIds().size()) {
            throw new Refused("row_outside_sheet");
        }
        StringBuilder t = new StringBuilder("@{").append(layout.colIds().get(col));
        boolean sameRow = rowNo == cellRow && !absRow;
        if (!sameRow) {
            t.append(',').append(layout.rowIds().get(rowNo - 1));
        }
        if (absCol || absRow) {
            if (sameRow) {
                t.append(',');
            }
            t.append(',').append(absCol ? "c" : "").append(absRow ? "r" : "");
        }
        return t.append('}').toString();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // ids -> Excel-style

    /** @param row 1-based row at which the formula is DRAWN (a filler-added row draws the same stored text at its own row) */
    public static String toExcel(String ids, int row, Layout layout) {
        Map<String, Integer> cols = index(layout.colIds());
        Map<String, Integer> rows = index(layout.rowIds());
        StringBuilder out = new StringBuilder();
        int i = 0;
        int n = ids.length();
        while (i < n) {
            char ch = ids.charAt(i);
            if (ch == '"') {
                int end = endOfText(ids, i);
                out.append(ids, i, end);
                i = end;
                continue;
            }
            if (ch == '@') {
                Matcher m = ID_TOKEN.matcher(ids).region(i, n);
                if (!m.lookingAt()) {
                    throw new Refused("bad_reference_token");
                }
                Integer col = cols.get(m.group(1));
                if (col == null) {
                    throw new Refused("unknown_column");
                }
                String rowId = m.group(2);
                int rowNo;
                if (rowId == null || rowId.isEmpty()) {
                    rowNo = row;
                } else {
                    Integer r = rows.get(rowId);
                    if (r == null) {
                        throw new Refused("unknown_row");
                    }
                    rowNo = r + 1;
                }
                String flags = m.group(3) == null ? "" : m.group(3);
                out.append(flags.contains("c") ? "$" : "").append(columnName(col)).append(flags.contains("r") ? "$" : "")
                        .append(rowNo);
                i = m.end();
                continue;
            }
            out.append(ch);
            i++;
        }
        return out.toString();
    }

    // ---------------------------------------------------------------------------------------------------------------

    /** Every identifier a stored formula names; the server checks that each still exists before it keeps the formula. */
    public static List<String> identifiers(String ids) {
        List<String> found = new ArrayList<>();
        Matcher m = ID_TOKEN.matcher(ids);
        while (m.find()) {
            found.add(m.group(1));
            if (m.group(2) != null && !m.group(2).isEmpty()) {
                found.add(m.group(2));
            }
        }
        return found;
    }

    /** How many A1 references the text holds outside text constants (for the cross-check against POI's tokens). */
    public static int countReferences(String excel) {
        int count = 0;
        int i = 0;
        int n = excel.length();
        while (i < n) {
            char ch = excel.charAt(i);
            if (ch == '"') {
                i = endOfText(excel, i);
                continue;
            }
            if (isWordStart(ch, excel, i)) {
                Matcher m = A1.matcher(excel).region(i, n);
                if (m.lookingAt()) {
                    int end = m.end();
                    boolean followedByWord = end < n && (Character.isLetterOrDigit(excel.charAt(end))
                            || excel.charAt(end) == '_' || excel.charAt(end) == '(' || excel.charAt(end) == '.');
                    if (!followedByWord) {
                        count++;
                        i = end;
                        continue;
                    }
                }
                int end = i;
                while (end < n && (Character.isLetterOrDigit(excel.charAt(end)) || excel.charAt(end) == '_'
                        || excel.charAt(end) == '.')) {
                    end++;
                }
                i = Math.max(end, i + 1);
                continue;
            }
            i++;
        }
        return count;
    }

    private static boolean isWordStart(char ch, String s, int i) {
        if (!(Character.isLetter(ch) || ch == '$' || ch == '_')) {
            return false;
        }
        if (i == 0) {
            return true;
        }
        char p = s.charAt(i - 1);
        return !(Character.isLetterOrDigit(p) || p == '_' || p == '.' || p == '$');
    }

    private static int endOfText(String s, int start) {
        int i = start + 1;
        while (i < s.length()) {
            if (s.charAt(i) == '"') {
                if (i + 1 < s.length() && s.charAt(i + 1) == '"') {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        throw new Refused("unclosed_text");
    }

    private static Map<String, Integer> index(List<String> ids) {
        Map<String, Integer> m = new HashMap<>();
        for (int i = 0; i < ids.size(); i++) {
            m.put(ids.get(i), i);
        }
        return m;
    }

    static int columnIndex(String letters) {
        int v = 0;
        for (char c : letters.toCharArray()) {
            v = v * 26 + (c - 'A' + 1);
        }
        return v - 1;
    }

    static String columnName(int index) {
        StringBuilder s = new StringBuilder();
        int n = index + 1;
        while (n > 0) {
            int r = (n - 1) % 26;
            s.insert(0, (char) ('A' + r));
            n = (n - 1) / 26;
        }
        return s.toString();
    }
}
