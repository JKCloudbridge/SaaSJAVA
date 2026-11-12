package spike;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * POC 2, WP5.3 (Java side). The translators of {@link IdFormulas} with the GROWING RANGE token: {@code @{COL,*}} is column COL over
 * every body row of the sheet, first to last, however many rows the filler adds; {@code @{COL,ROW}:@{COL,*}} is a range with an
 * open end. Only a range over EXACTLY every body row is written as the token; every other range keeps its two row identifiers.
 * A row flagged as a total is not a body row, so a total is never part of its own range.
 */
public final class IdFormulas2 {

    public record Layout(List<String> colIds, List<String> rowIds, List<String> bodyRowIds) {
    }

    private static final Pattern A1 = Pattern.compile("(\\$?)([A-Za-z]{1,3})(\\$?)([0-9]{1,7})");
    private static final Pattern TOKEN = Pattern.compile("@\\{([A-Za-z0-9_-]{1,40})(?:,([A-Za-z0-9_-]{0,40}|\\*))?(?:,([cr]{0,2}))?}");

    private IdFormulas2() {
    }

    private record Ref(int end, int col, int row, boolean absC, boolean absR) {
    }

    private static Ref refAt(String s, int i) {
        char ch = s.charAt(i);
        if (!(Character.isLetter(ch) || ch == '$' || ch == '_')) {
            return null;
        }
        if (i > 0) {
            char p = s.charAt(i - 1);
            if (Character.isLetterOrDigit(p) || p == '_' || p == '.' || p == '$') {
                return null;
            }
        }
        Matcher m = A1.matcher(s).region(i, s.length());
        if (!m.lookingAt()) {
            return null;
        }
        int end = m.end();
        if (end < s.length() && (Character.isLetterOrDigit(s.charAt(end)) || s.charAt(end) == '_' || s.charAt(end) == '('
                || s.charAt(end) == '.')) {
            return null;
        }
        return new Ref(end, IdFormulas.columnIndex(m.group(2).toUpperCase(Locale.ROOT)), Integer.parseInt(m.group(4)),
                !m.group(1).isEmpty(), !m.group(3).isEmpty());
    }

    public static String toIds(String excel, int row, Layout l) {
        StringBuilder out = new StringBuilder();
        int bodyFirst = l.bodyRowIds().isEmpty() ? -1 : l.rowIds().indexOf(l.bodyRowIds().get(0)) + 1;
        int bodyLast = l.bodyRowIds().isEmpty() ? -1 : l.rowIds().indexOf(l.bodyRowIds().get(l.bodyRowIds().size() - 1)) + 1;
        int i = 0;
        int n = excel.length();
        while (i < n) {
            char ch = excel.charAt(i);
            if (ch == '"') {
                int end = i + 1;
                while (end < n && !(excel.charAt(end) == '"' && !(end + 1 < n && excel.charAt(end + 1) == '"'))) {
                    end += excel.charAt(end) == '"' ? 2 : 1;
                }
                out.append(excel, i, Math.min(n, end + 1));
                i = Math.min(n, end + 1);
                continue;
            }
            if (ch == '@' || ch == '{' || ch == '[' || ch == '!' || ch == '\'') {
                throw new IdFormulas.Refused("character_not_allowed");
            }
            Ref r = refAt(excel, i);
            if (r != null) {
                if (r.end() < n && excel.charAt(r.end()) == ':') {
                    Ref r2 = r.end() + 1 < n ? refAt(excel, r.end() + 1) : null;
                    if (r2 != null && r2.col() == r.col() && bodyFirst > 0 && r.row() == bodyFirst && r2.row() == bodyLast
                            && r.col() < l.colIds().size()) {
                        out.append("@{").append(l.colIds().get(r.col())).append(",*}");
                        i = r2.end();
                        continue;
                    }
                }
                if (r.col() >= l.colIds().size()) {
                    throw new IdFormulas.Refused("column_outside_sheet");
                }
                if (r.row() < 1 || r.row() > l.rowIds().size()) {
                    throw new IdFormulas.Refused("row_outside_sheet");
                }
                boolean same = r.row() == row && !r.absR();
                out.append("@{").append(l.colIds().get(r.col()));
                if (!same) {
                    out.append(',').append(l.rowIds().get(r.row() - 1));
                }
                if (r.absC() || r.absR()) {
                    out.append(same ? ",," : ",").append(r.absC() ? "c" : "").append(r.absR() ? "r" : "");
                }
                out.append('}');
                i = r.end();
                continue;
            }
            if (Character.isLetter(ch) || ch == '_' || ch == '$') {
                int e = i;
                while (e < n && (Character.isLetterOrDigit(excel.charAt(e)) || excel.charAt(e) == '_' || excel.charAt(e) == '.')) {
                    e++;
                }
                out.append(excel, i, Math.max(e, i + 1));
                i = Math.max(e, i + 1);
                continue;
            }
            out.append(ch);
            i++;
        }
        return out.toString();
    }

    public static String toExcel(String ids, int row, Layout l) {
        StringBuilder out = new StringBuilder();
        int firstNo = l.bodyRowIds().isEmpty() ? -1 : l.rowIds().indexOf(l.bodyRowIds().get(0)) + 1;
        int lastNo = l.bodyRowIds().isEmpty() ? -1 : l.rowIds().indexOf(l.bodyRowIds().get(l.bodyRowIds().size() - 1)) + 1;
        int i = 0;
        int n = ids.length();
        while (i < n) {
            char ch = ids.charAt(i);
            if (ch == '"') {
                int end = i + 1;
                while (end < n && !(ids.charAt(end) == '"' && !(end + 1 < n && ids.charAt(end + 1) == '"'))) {
                    end += ids.charAt(end) == '"' ? 2 : 1;
                }
                out.append(ids, i, Math.min(n, end + 1));
                i = Math.min(n, end + 1);
                continue;
            }
            if (ch == '@') {
                Matcher m = TOKEN.matcher(ids).region(i, n);
                if (!m.lookingAt()) {
                    throw new IdFormulas.Refused("bad_reference_token");
                }
                int col = l.colIds().indexOf(m.group(1));
                if (col < 0) {
                    throw new IdFormulas.Refused("unknown_column");
                }
                String letters = IdFormulas.columnName(col);
                String rowPart = m.group(2);
                String flags = m.group(3) == null ? "" : m.group(3);
                int after = m.end();
                if ("*".equals(rowPart)) {
                    if (firstNo < 0) {
                        throw new IdFormulas.Refused("no_body_rows");
                    }
                    if (after < n && ids.charAt(after) == ':') {
                        out.append(letters).append(firstNo);
                    } else if (out.length() > 0 && out.charAt(out.length() - 1) == ':') {
                        out.append(letters).append(lastNo);
                    } else {
                        out.append(letters).append(firstNo).append(':').append(letters).append(lastNo);
                    }
                } else {
                    int rowNo = row;
                    if (rowPart != null && !rowPart.isEmpty()) {
                        int r = l.rowIds().indexOf(rowPart);
                        if (r < 0) {
                            throw new IdFormulas.Refused("unknown_row");
                        }
                        rowNo = r + 1;
                    }
                    out.append(flags.contains("c") ? "$" : "").append(letters).append(flags.contains("r") ? "$" : "").append(rowNo);
                }
                i = after;
                continue;
            }
            out.append(ch);
            i++;
        }
        return out.toString();
    }
}
