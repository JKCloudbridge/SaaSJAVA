package app.platform.sheets.internal.formula;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Translates between the text a spreadsheet program writes ({@code SUM(D4:D9)}) and the stored form in which every
 * reference names its column and row by permanent identifier (SH2, ADR-0252). It is a scanner over the text, not a
 * parser: it skips text constants, rewrites each plain reference, and <strong>refuses</strong> a reference outside the
 * sheet, another sheet, another file, a name, the characters at-sign, opening brace, opening bracket, exclamation mark and apostrophe, and a text that is not closed.
 * Inserting,
 * moving or removing a row or column rewrites no stored formula; only the text drawn back changes.
 *
 * <p>The same translators exist in the browser; both read the same case file.
 */
public final class FormulaTranslator {

    private static final int MOST_ROW = 9_999_999;
    /** The last column a spreadsheet has: XFD. */
    private static final int MOST_COLUMN = 16_383;

    private FormulaTranslator() {
    }

    /**
     * What the translators need to know about the sheet.
     *
     * @param columnIds the identifiers of the columns in order (position 0 is column A)
     * @param rowIds the identifiers of the rows that have one, in order: the header rows, then the named rows of a
     *        sheet that names its rows. An empty identifier stands for a row that has none (a row of a file that
     *        does not become a row of the sheet): a formula cannot point at it
     * @param growing whether the filler adds all the rows (the body rows have no identifier and follow the header rows)
     * @param bodyRows for a growing sheet, how many body rows are drawn now (at least 1 to draw a whole-body range)
     */
    public record Layout(List<String> columnIds, List<String> rowIds, boolean growing, int bodyRows) {

        /** Copies the lists. */
        public Layout {
            columnIds = List.copyOf(columnIds);
            rowIds = List.copyOf(rowIds);
        }

        /** The 1-based position of the first body row of a growing sheet. */
        int firstBody() {
            return rowIds.size() + 1;
        }

        /** The 1-based position of the last body row drawn now. */
        int lastBody() {
            return rowIds.size() + bodyRows;
        }
    }

    // ---- spreadsheet text to the stored form ----

    private record Found(int end, int column, int row, boolean absoluteColumn, boolean absoluteRow) {
    }

    /**
     * Spreadsheet text to the stored form.
     *
     * @param excel the formula as a spreadsheet writes it, without the equals sign
     * @param row the 1-based position of the cell that holds the formula
     * @param layout the sheet
     * @return the stored form
     * @throws FormulaRefused when a reference cannot be named by identifiers, or a character is not allowed
     */
    public static String toIds(String excel, int row, Layout layout) {
        if (excel.length() > FormulaLimits.DEFAULTS.maxChars()) {
            throw new FormulaRefused("too_long");
        }
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < excel.length()) {
            char c = excel.charAt(i);
            if (c == '"') {
                int end = endOfText(excel, i);
                out.append(excel, i, end);
                i = end;
                continue;
            }
            if ("@{[!'".indexOf(c) >= 0) {
                throw new FormulaRefused("character_not_allowed");
            }
            Found found = referenceAt(excel, i);
            if (found != null) {
                if (found.end() < excel.length() && excel.charAt(found.end()) == ':') {
                    Found second = referenceAt(excel, found.end() + 1);
                    if (second != null && growingRange(found, second, layout)) {
                        out.append("@{").append(layout.columnIds().get(found.column())).append(",*}");
                        i = second.end();
                        continue;
                    }
                }
                out.append(token(found, row, layout));
                i = found.end();
                continue;
            }
            if (wordStart(excel, i)) {
                int end = i;
                while (end < excel.length() && wordChar(excel.charAt(end))) {
                    end++;
                }
                if (end == i) {
                    out.append(c);
                    i++;
                } else {
                    out.append(excel, i, end);
                    i = end;
                }
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    /**
     * Moves every relative reference of a formula as a spreadsheet does when the formula is copied to another cell:
     * a part marked absolute stays, the rest moves by the given rows and columns. This is how the members of a shared
     * formula of a file get their text from the first cell of the group, which is the only one that holds it.
     *
     * @param excel the formula in spreadsheet text
     * @param rows how many rows down (negative is up)
     * @param columns how many columns right (negative is left)
     * @return the moved text
     * @throws FormulaRefused when a reference would leave the sheet
     */
    public static String shift(String excel, int rows, int columns) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < excel.length()) {
            char c = excel.charAt(i);
            if (c == '"') {
                int end = endOfText(excel, i);
                out.append(excel, i, end);
                i = end;
                continue;
            }
            Found found = referenceAt(excel, i);
            if (found != null) {
                int column = found.absoluteColumn() ? found.column() : found.column() + columns;
                int row = found.absoluteRow() ? found.row() : found.row() + rows;
                if (column < 0 || column > MOST_COLUMN || row < 1 || row > MOST_ROW) {
                    throw new FormulaRefused("row_outside_sheet");
                }
                out.append(found.absoluteColumn() ? "$" : "").append(letters(column))
                        .append(found.absoluteRow() ? "$" : "").append(row);
                i = found.end();
                continue;
            }
            if (wordStart(excel, i)) {
                int end = i;
                while (end < excel.length() && wordChar(excel.charAt(end))) {
                    end++;
                }
                if (end == i) {
                    out.append(c);
                    i++;
                } else {
                    out.append(excel, i, end);
                    i = end;
                }
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static boolean growingRange(Found a, Found b, Layout layout) {
        return layout.growing() && layout.bodyRows() >= 1 && a.column() == b.column()
                && a.column() < layout.columnIds().size() && !a.absoluteRow() && !b.absoluteRow()
                && !a.absoluteColumn() && !b.absoluteColumn() && a.row() == layout.firstBody()
                && b.row() == layout.lastBody();
    }

    private static String token(Found found, int row, Layout layout) {
        if (found.column() >= layout.columnIds().size()) {
            throw new FormulaRefused("column_outside_sheet");
        }
        if (found.row() < 1) {
            throw new FormulaRefused("row_outside_sheet");
        }
        boolean same = found.row() == row && !found.absoluteRow();
        String rowId = null;
        if (!same) {
            if (found.row() > layout.rowIds().size() || layout.rowIds().get(found.row() - 1).isEmpty()) {
                throw new FormulaRefused("row_outside_sheet");
            }
            rowId = layout.rowIds().get(found.row() - 1);
        }
        StringBuilder out = new StringBuilder("@{").append(layout.columnIds().get(found.column()));
        if (rowId != null) {
            out.append(',').append(rowId);
        }
        if (found.absoluteColumn() || found.absoluteRow()) {
            out.append(rowId == null ? ",," : ",");
            if (found.absoluteColumn()) {
                out.append('c');
            }
            if (found.absoluteRow()) {
                out.append('r');
            }
        }
        return out.append('}').toString();
    }

    private static int endOfText(String s, int start) {
        for (int k = start + 1; k < s.length(); k++) {
            if (s.charAt(k) == '"') {
                if (k + 1 < s.length() && s.charAt(k + 1) == '"') {
                    k++;
                    continue;
                }
                return k + 1;
            }
        }
        throw new FormulaRefused("unclosed_text");
    }

    private static boolean letter(char c) {
        return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z';
    }

    private static boolean digit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean wordChar(char c) {
        return letter(c) || digit(c) || c == '_' || c == '.';
    }

    private static boolean wordStart(String s, int i) {
        char c = s.charAt(i);
        if (!(letter(c) || c == '$' || c == '_')) {
            return false;
        }
        if (i == 0) {
            return true;
        }
        char before = s.charAt(i - 1);
        return !(wordChar(before) || before == '$');
    }

    /** A plain reference ({@code A1}, {@code $A$1}) that starts at the position, or null. */
    private static Found referenceAt(String s, int i) {
        if (!wordStart(s, i)) {
            return null;
        }
        int k = i;
        boolean absoluteColumn = false;
        if (s.charAt(k) == '$') {
            absoluteColumn = true;
            k++;
        }
        int lettersStart = k;
        while (k < s.length() && letter(s.charAt(k)) && k - lettersStart < 3) {
            k++;
        }
        if (k == lettersStart) {
            return null;
        }
        int lettersEnd = k;
        boolean absoluteRow = false;
        if (k < s.length() && s.charAt(k) == '$') {
            absoluteRow = true;
            k++;
        }
        int digitsStart = k;
        while (k < s.length() && digit(s.charAt(k)) && k - digitsStart < 7) {
            k++;
        }
        if (k == digitsStart) {
            return null;
        }
        if (k < s.length() && (letter(s.charAt(k)) || digit(s.charAt(k)) || s.charAt(k) == '_' || s.charAt(k) == '('
                || s.charAt(k) == '.')) {
            return null;
        }
        return new Found(k, columnIndex(s.substring(lettersStart, lettersEnd)), Integer.parseInt(s.substring(
                digitsStart, k)), absoluteColumn, absoluteRow);
    }

    // ---- the stored form to spreadsheet text ----

    /**
     * The stored form drawn as spreadsheet text at a row.
     *
     * @param stored the stored form
     * @param row the 1-based position of the cell the text is drawn at
     * @param layout the sheet
     * @return the text, without the equals sign
     * @throws FormulaRefused when a token names a column or row that does not exist
     */
    public static String toExcel(String stored, int row, Layout layout) {
        Map<String, Integer> columns = new HashMap<>();
        for (int i = 0; i < layout.columnIds().size(); i++) {
            columns.put(layout.columnIds().get(i), i);
        }
        Map<String, Integer> rows = new HashMap<>();
        for (int i = 0; i < layout.rowIds().size(); i++) {
            rows.put(layout.rowIds().get(i), i);
        }
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < stored.length()) {
            char c = stored.charAt(i);
            if (c == '"') {
                int end = endOfText(stored, i);
                out.append(stored, i, end);
                i = end;
                continue;
            }
            if (c != '@') {
                out.append(c);
                i++;
                continue;
            }
            int close = stored.indexOf('}', i);
            if (!stored.startsWith("@{", i) || close < 0) {
                throw new FormulaRefused("bad_reference_token");
            }
            Node.Ref ref = FormulaParser.reference(stored.substring(i + 2, close));
            Integer column = columns.get(ref.column());
            if (column == null) {
                throw new FormulaRefused("unknown_column");
            }
            String letters = letters(column);
            char after = close + 1 < stored.length() ? stored.charAt(close + 1) : ' ';
            char before = out.length() > 0 ? out.charAt(out.length() - 1) : ' ';
            if (ref.growing()) {
                if (!layout.growing()) {
                    throw new FormulaRefused("growing_not_allowed");
                }
                if (layout.bodyRows() < 1) {
                    throw new FormulaRefused("no_body_rows");
                }
                if (after == ':') {
                    out.append(letters).append(layout.firstBody());
                } else if (before == ':') {
                    out.append(letters).append(layout.lastBody());
                } else {
                    out.append(letters).append(layout.firstBody()).append(':').append(letters)
                            .append(layout.lastBody());
                }
            } else {
                int position = row;
                if (ref.row() != null) {
                    Integer index = rows.get(ref.row());
                    if (index == null) {
                        throw new FormulaRefused("unknown_row");
                    }
                    position = index + 1;
                }
                if (position < 1 || position > MOST_ROW) {
                    throw new FormulaRefused("row_outside_sheet");
                }
                out.append(ref.absoluteColumn() ? "$" : "").append(letters).append(ref.absoluteRow() ? "$" : "")
                        .append(position);
            }
            i = close + 1;
        }
        return out.toString();
    }

    // ---- column letters ----

    /** The letters of a column from its 0-based position. */
    public static String letters(int index) {
        StringBuilder s = new StringBuilder();
        for (int n = index + 1; n > 0; n = (n - 1) / 26) {
            s.insert(0, (char) ('A' + (n - 1) % 26));
        }
        return s.toString();
    }

    private static int columnIndex(String letters) {
        int value = 0;
        for (char c : letters.toUpperCase(java.util.Locale.ROOT).toCharArray()) {
            value = value * 26 + c - 'A' + 1;
        }
        return value - 1;
    }
}
