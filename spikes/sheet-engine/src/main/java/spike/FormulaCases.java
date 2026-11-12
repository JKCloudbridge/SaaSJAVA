package spike;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellValue;
import org.apache.poi.ss.usermodel.FormulaError;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * POC 2, WP5.2. Runs the shared case file (spikes/sheet-grid/lib/poc2-formula-cases.txt) through POI's own evaluator. The expected
 * value in the file is the documented behaviour of the spreadsheet function, written before any engine was run; a case on which POI
 * differs is printed and decides whether the function can stay in the set. Usage: FormulaCases <cases file> [-v]
 */
public final class FormulaCases {

    record Case(String name, String formula, String inputs, String expected) {
    }

    private FormulaCases() {
    }

    static List<Case> load(Path file) throws Exception {
        List<Case> out = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] p = line.split("\\|", -1);
            if (p.length != 4) {
                throw new IllegalStateException("bad case line: " + line);
            }
            out.add(new Case(p[0].trim(), p[1].trim(), p[2].trim(), p[3].trim()));
        }
        return out;
    }

    /** Splits "A1=1; A2="x"; A3=TRUE" respecting quotes. */
    static List<String[]> inputs(String spec) {
        List<String[]> out = new ArrayList<>();
        if (spec.isEmpty()) {
            return out;
        }
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        List<String> parts = new ArrayList<>();
        for (char ch : spec.toCharArray()) {
            if (ch == '"') {
                quoted = !quoted;
            }
            if (ch == ';' && !quoted) {
                parts.add(cur.toString().trim());
                cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        parts.add(cur.toString().trim());
        for (String part : parts) {
            int eq = part.indexOf('=');
            out.add(new String[] {part.substring(0, eq).trim(), part.substring(eq + 1).trim()});
        }
        return out;
    }

    static int col(String ref) {
        return Character.toUpperCase(ref.charAt(0)) - 'A';
    }

    static int row(String ref) {
        return Integer.parseInt(ref.substring(1)) - 1;
    }

    public static void main(String[] args) throws Exception {
        List<Case> cases = load(Path.of(args[0]));
        boolean verbose = args.length > 2;
        StringBuilder results = new StringBuilder();
        int pass = 0;
        int fail = 0;
        for (Case c : cases) {
            String got;
            try (XSSFWorkbook wb = new XSSFWorkbook()) {
                Sheet sh = wb.createSheet("s");
                for (String[] in : inputs(c.inputs())) {
                    Row r = sh.getRow(row(in[0])) == null ? sh.createRow(row(in[0])) : sh.getRow(row(in[0]));
                    Cell cell = r.createCell(col(in[0]));
                    String v = in[1];
                    if (v.startsWith("\"")) {
                        cell.setCellValue(v.substring(1, v.length() - 1));
                    } else if (v.equals("TRUE") || v.equals("FALSE")) {
                        cell.setCellValue(v.equals("TRUE"));
                    } else if (v.startsWith("#")) {
                        cell.setCellErrorValue(FormulaError.forString(v).getCode());
                    } else {
                        cell.setCellValue(Double.parseDouble(v));
                    }
                }
                Row r0 = sh.getRow(0) == null ? sh.createRow(0) : sh.getRow(0);
                Cell k1 = r0.createCell(10);
                try {
                    k1.setCellFormula(c.formula());
                    CellValue val = wb.getCreationHelper().createFormulaEvaluator().evaluate(k1);
                    got = switch (val.getCellType()) {
                        case NUMERIC -> number(val.getNumberValue());
                        case STRING -> "\"" + val.getStringValue() + "\"";
                        case BOOLEAN -> val.getBooleanValue() ? "TRUE" : "FALSE";
                        case ERROR -> FormulaError.forInt(val.getErrorValue()).getString();
                        case BLANK -> "0";
                        default -> "?" + val.getCellType();
                    };
                } catch (RuntimeException e) {
                    got = "REFUSED " + e.getClass().getSimpleName();
                }
            }
            results.append(c.name()).append('\t').append(got).append('\n');
            boolean same = same(c.expected(), got);
            if (same) {
                pass++;
            } else {
                fail++;
            }
            if (!same || verbose) {
                System.out.println((same ? "ok   " : "POI DIFFERS ") + c.name() + " | " + c.formula() + " | expected " + c.expected()
                        + " | POI " + got);
            }
        }
        if (args.length > 1 && !args[1].equals("-")) {
            Files.writeString(Path.of(args[1]), results.toString(), StandardCharsets.UTF_8);
        }
        System.out.println("POI on " + cases.size() + " cases: equal to the documented value " + pass + ", differ " + fail);
    }

    static String number(double d) {
        return d == Math.rint(d) && Math.abs(d) < 1e15 ? Long.toString((long) d) : Double.toString(d);
    }

    static boolean same(String expected, String got) {
        if (expected.startsWith("\"") || expected.startsWith("#") || expected.equals("TRUE") || expected.equals("FALSE")) {
            return expected.equals(got);
        }
        try {
            return Math.abs(Double.parseDouble(expected) - Double.parseDouble(got)) <= 1e-9;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    static {
        Locale.setDefault(Locale.US);
    }
}
