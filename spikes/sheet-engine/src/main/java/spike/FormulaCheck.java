package spike;

import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.CellValue;
import org.apache.poi.ss.usermodel.FormulaError;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * WP1.2: (a) every real formula of the two workbooks is checked against the whitelist and evaluated by POI, and compared with
 * the value the spreadsheet program stored; (b) a table of small cases for the situations the prompt names (blank, text to number, errors).
 * Output holds coordinates, kinds and counts only.
 */
public final class FormulaCheck {

    private static final double TOLERANCE = 1e-9;

    private FormulaCheck() {
    }

    public static void main(String[] args) throws Exception {
        int differences = 0;
        for (String path : args) {
            differences += realFormulas(new File(path));
        }
        differences += synthetic();
        System.out.println("TOTAL differences: " + differences);
    }

    static int realFormulas(File file) throws Exception {
        int formulas = 0;
        int whitelisted = 0;
        int equal = 0;
        int differences = 0;
        int cachedErrors = 0;
        Map<String, Integer> refused = new TreeMap<>();
        try (XSSFWorkbook wb = new XSSFWorkbook(OPCPackage.open(file, PackageAccess.READ))) {
            FormulaEvaluator ev = wb.getCreationHelper().createFormulaEvaluator();
            for (int s = 0; s < wb.getNumberOfSheets(); s++) {
                Sheet sheet = wb.getSheetAt(s);
                for (Row row : sheet) {
                    for (Cell cell : row) {
                        if (cell.getCellType() != CellType.FORMULA) {
                            continue;
                        }
                        formulas++;
                        List<String> problems = Whitelist.check(wb, s, cell.getCellFormula());
                        if (!problems.isEmpty()) {
                            problems.forEach(p -> refused.merge(p, 1, Integer::sum));
                            continue;
                        }
                        whitelisted++;
                        CellValue got = ev.evaluate(cell);
                        String why = compare(cell, got);
                        if (cell.getCachedFormulaResultType() == CellType.ERROR) {
                            cachedErrors++;
                        }
                        if (why == null) {
                            equal++;
                        } else {
                            differences++;
                            System.out.println("  DIFFERENCE sheet#" + s + " " + cell.getAddress() + ": " + why);
                        }
                    }
                }
            }
        }
        System.out.println("workbook formulas=" + formulas + " inside whitelist=" + whitelisted + " refused=" + refused
                + " equal to the spreadsheet program's stored value=" + equal + " (of which the spreadsheet program stored an error: " + cachedErrors
                + ") differences=" + differences);
        return differences;
    }

    /** Null when equal. Describes kinds and numbers, never text. */
    private static String compare(Cell cell, CellValue got) {
        switch (cell.getCachedFormulaResultType()) {
            case NUMERIC -> {
                double want = cell.getNumericCellValue();
                if (got.getCellType() != CellType.NUMERIC) {
                    return "stored number, evaluator gave " + got.getCellType();
                }
                double delta = Math.abs(want - got.getNumberValue());
                return delta <= TOLERANCE ? null : "numbers differ by " + delta;
            }
            case STRING -> {
                if (got.getCellType() != CellType.STRING) {
                    return "stored text, evaluator gave " + got.getCellType();
                }
                return cell.getStringCellValue().equals(got.getStringValue()) ? null : "texts differ";
            }
            case BOOLEAN -> {
                return got.getCellType() == CellType.BOOLEAN && cell.getBooleanCellValue() == got.getBooleanValue()
                        ? null : "booleans differ";
            }
            case ERROR -> {
                if (got.getCellType() != CellType.ERROR) {
                    return "stored error " + FormulaError.forInt(cell.getErrorCellValue()) + ", evaluator gave "
                            + got.getCellType();
                }
                return cell.getErrorCellValue() == got.getErrorValue() ? null
                        : "errors differ: stored " + FormulaError.forInt(cell.getErrorCellValue()) + " evaluator "
                                + FormulaError.forInt(got.getErrorValue());
            }
            default -> {
                return "unexpected stored type " + cell.getCachedFormulaResultType();
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------------

    /** One small case. Inputs are cells A1..; the expectation is the documented spreadsheet behaviour (not run in a spreadsheet program). */
    private record Case(String name, String formula, Object[] inputs, Object expected) {
    }

    private static Case c(String name, String formula, Object expected, Object... inputs) {
        return new Case(name, formula, inputs, expected);
    }

    static int synthetic() {
        // null input = blank cell; "" (empty string constant) is written as an empty text cell
        Object blank = null;
        List<Case> cases = List.of(
                c("blank in arithmetic", "A1+1", 1.0, blank),
                c("number as text in arithmetic", "\"5\"+1", 6.0),
                c("text cell in arithmetic", "A1+1", "#VALUE!", "abc"),
                c("divide by zero", "1/0", "#DIV/0!"),
                c("error propagates through IF condition", "IF(1/0>0,1,2)", "#DIV/0!"),
                c("IF blank guard, blank", "IF(A1=\"\",\"\",A1*2)", "", blank),
                c("IF blank guard, number", "IF(A1=\"\",\"\",A1*2)", 8.0, 4.0),
                c("SUM range ignores text and blank", "SUM(A1:A3)", 1.0, 1.0, "5", blank),
                c("SUM direct text argument is converted", "SUM(\"5\",1)", 6.0),
                c("MIN of blanks", "MIN(A1:A2)", 0.0, blank, blank),
                c("MAX ignores blank", "MAX(A1:A2)", -3.0, -3.0, blank),
                c("MIN with division error inside", "MIN(100,(B1/A1)*100)", "#DIV/0!", 0.0, 5.0),
                c("ROUND half up", "ROUND(2.5,0)", 3.0),
                c("ROUND half away from zero", "ROUND(-2.5,0)", -3.0),
                c("ROUND 2.675 to 2 places (decimal view)", "ROUND(2.675,2)", 2.68),
                c("ROUND 1.005 to 2 places (decimal view)", "ROUND(1.005,2)", 1.01),
                c("AND ignores a blank reference", "AND(TRUE,A1)", true, blank),
                c("OR of false", "OR(FALSE,FALSE)", false),
                c("AND of numbers", "AND(1,0)", false),
                c("ISBLANK of blank", "ISBLANK(A1)", true, blank),
                c("blank equals zero", "A1=0", true, blank),
                c("zero is not the empty string", "A1=\"\"", false, 0.0),
                c("text comparison ignores case", "\"a\"=\"A\"", true),
                c("IF with text condition", "IF(A1,1,2)", "#VALUE!", "x"),
                c("TRUE counts as 1", "1+TRUE", 2.0),
                c("binary floating point", "0.1+0.2", 0.3),
                c("empty text constant in arithmetic", "A1*2", "#VALUE!", ""),
                c("the supplier-sheet pattern, blank first cell",
                        "IF(A1=\"\",\"\",MIN(100,((B1/A1)+(0.5*(C1-B1)/A1))*100))", "", blank, 5.0, 6.0),
                c("the supplier-sheet pattern, values",
                        "IF(A1=\"\",\"\",MIN(100,((B1/A1)+(0.5*(C1-B1)/A1))*100))", 95.0, 10.0, 9.0, 10.0),
                c("the supplier-sheet pattern, capped at 100",
                        "IF(A1=\"\",\"\",MIN(100,((B1/A1)+(0.5*(C1-B1)/A1))*100))", 100.0, 10.0, 10.0, 12.0),
                c("the supplier-sheet pattern, below the cap",
                        "IF(A1=\"\",\"\",MIN(100,((B1/A1)+(0.5*(C1-B1)/A1))*100))", 75.0, 10.0, 7.0, 8.0),
                c("percent literal", "50%*2", 1.0),
                c("string concatenation is not whitelisted but parses", "\"a\"&\"b\"", "ab"));
        int differences = 0;
        int run = 0;
        for (Case k : cases) {
            run++;
            String got = evaluate(k);
            String want = describe(k.expected);
            boolean same = got.equals(want)
                    || k.expected instanceof Double d && close(got, d);
            if (!same) {
                differences++;
                System.out.println("  CASE DIFFERS [" + k.name + "] expected " + want + " got " + got);
            }
        }
        System.out.println("small cases run=" + run + " differences from documented spreadsheet behaviour=" + differences);
        return differences;
    }

    private static boolean close(String got, double want) {
        try {
            return Math.abs(Double.parseDouble(got) - want) <= TOLERANCE;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static String describe(Object o) {
        if (o instanceof Double d) {
            return d == Math.rint(d) && Math.abs(d) < 1e15 ? Long.toString(d.longValue()) : Double.toString(d);
        }
        return String.valueOf(o).toUpperCase(Locale.ROOT).equals("TRUE") || String.valueOf(o).equals("false")
                ? String.valueOf(o).toLowerCase(Locale.ROOT) : String.valueOf(o);
    }

    private static String evaluate(Case k) {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sh = wb.createSheet("s");
            Row row = sh.createRow(0);
            for (int i = 0; i < k.inputs.length; i++) {
                Object v = k.inputs[i];
                if (v == null) {
                    continue;
                }
                Cell cell = row.createCell(i);
                if (v instanceof Double d) {
                    cell.setCellValue(d);
                } else {
                    cell.setCellValue((String) v);
                }
            }
            Cell out = row.createCell(10);
            out.setCellFormula(k.formula);
            CellValue v = wb.getCreationHelper().createFormulaEvaluator().evaluate(out);
            return switch (v.getCellType()) {
                case NUMERIC -> describe(v.getNumberValue());
                case STRING -> v.getStringValue();
                case BOOLEAN -> String.valueOf(v.getBooleanValue());
                case ERROR -> FormulaError.forInt(v.getErrorValue()).getString();
                default -> "?" + v.getCellType();
            };
        } catch (Exception e) {
            return "EXCEPTION " + e.getClass().getSimpleName();
        }
    }
}
