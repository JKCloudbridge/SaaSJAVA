package app.platform.sheets.internal.formula;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The one file of formula cases that the Java tests and the TypeScript tests both read (SH2). It holds no text of
 * a real
 * form. See the header of {@code formula-cases.txt} for the sections.
 */
final class CaseFile {

    /** One line of the file, split on the bars. */
    record Line(String section, int number, List<String> fields) {
        String name() {
            return fields.get(0);
        }
    }

    static final Path FILE = Path.of("src/test/resources/sheets/formula-cases.txt");

    private CaseFile() {
    }

    /** Every line of one section. */
    static List<Line> section(String name) {
        List<Line> out = new ArrayList<>();
        String current = "eval";
        int number = 0;
        try {
            for (String line : Files.readAllLines(FILE, StandardCharsets.UTF_8)) {
                number++;
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                if (line.startsWith("[") && line.endsWith("]")) {
                    current = line.substring(1, line.length() - 1);
                    continue;
                }
                if (current.equals(name)) {
                    List<String> fields = new ArrayList<>();
                    for (String field : line.split("\\|", -1)) {
                        fields.add(field.trim());
                    }
                    out.add(new Line(current, number, fields));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    /** The thirty columns and sixty rows every case but [growing] uses. */
    static FormulaTranslator.Layout flat() {
        List<String> columns = new ArrayList<>();
        for (int i = 1; i <= 30; i++) {
            columns.add("c" + i);
        }
        List<String> rows = new ArrayList<>();
        for (int i = 1; i <= 60; i++) {
            rows.add("r" + i);
        }
        return new FormulaTranslator.Layout(columns, rows, false, 0);
    }

    /** The values of a case, by "c1|r1". */
    static Map<String, Object> inputs(String spec) {
        Map<String, Object> cells = new HashMap<>();
        for (String part : split(spec)) {
            int equals = part.indexOf('=');
            String ref = part.substring(0, equals).trim();
            String value = part.substring(equals + 1).trim();
            int column = ref.charAt(0) - 'A' + 1;
            int row = Integer.parseInt(ref.substring(1));
            cells.put("c" + column + "|r" + row, value(value));
        }
        return cells;
    }

    private static List<String> split(String spec) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (char c : spec.toCharArray()) {
            if (c == '"') {
                quoted = !quoted;
            }
            if (c == ';' && !quoted) {
                parts.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        if (!current.toString().isBlank()) {
            parts.add(current.toString().trim());
        }
        return parts;
    }

    /** A value written in the file: "text", TRUE, FALSE, #CODE or a number. */
    static Object value(String text) {
        if (text.startsWith("\"")) {
            return text.substring(1, text.length() - 1);
        }
        if (text.equals("TRUE")) {
            return Boolean.TRUE;
        }
        if (text.equals("FALSE")) {
            return Boolean.FALSE;
        }
        if (text.startsWith("#")) {
            return new XlError(text);
        }
        return Double.valueOf(text);
    }

    /** Whether an answer is the expected one. */
    static boolean same(String expected, Object got) {
        Object want = value(expected);
        if (want instanceof Double d) {
            return got instanceof Double g && Math.abs(g - d) <= 1e-9;
        }
        return want.equals(got);
    }

    /** A grid over the cells of a case. */
    static FormulaEvaluator.Grid grid(Map<String, Object> cells, List<String> bodyRows) {
        return new FormulaEvaluator.Grid() {
            @Override
            public Object cell(String column, String row) {
                return cells.get(column + "|" + row);
            }

            @Override
            public List<Object> range(String fromColumn, String fromRow, String toColumn, String toRow) {
                int c0 = Math.min(index(fromColumn), index(toColumn));
                int c1 = Math.max(index(fromColumn), index(toColumn));
                int r0 = Math.min(index(fromRow), index(toRow));
                int r1 = Math.max(index(fromRow), index(toRow));
                List<Object> out = new ArrayList<>();
                for (int r = r0; r <= r1; r++) {
                    for (int c = c0; c <= c1; c++) {
                        out.add(cells.get("c" + c + "|r" + r));
                    }
                }
                return out;
            }

            @Override
            public List<String> bodyRows() {
                return bodyRows;
            }

            private int index(String id) {
                return Integer.parseInt(id.substring(1));
            }
        };
    }
}
