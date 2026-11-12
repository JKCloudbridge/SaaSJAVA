package spike;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.ss.formula.FormulaParser;
import org.apache.poi.ss.formula.FormulaType;
import org.apache.poi.ss.formula.ptg.AreaPtg;
import org.apache.poi.ss.formula.ptg.Ptg;
import org.apache.poi.ss.formula.ptg.RefPtg;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.CellValue;
import org.apache.poi.ss.usermodel.FormulaError;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFEvaluationWorkbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import spike.IdFormulas.Layout;
import spike.IdFormulas.Refused;

/** WP4 checks. Prints counts and kinds only; no formula or value text. */
public final class IdFormulaCheck {

    private static int failures;

    private IdFormulaCheck() {
    }

    private record Found(int sheet, int row, int col, String excel, double cachedNumber, String cachedText, int cachedKind,
            byte cachedError) {
    }

    public static void main(String[] args) throws Exception {
        List<Found> all = new ArrayList<>();
        for (String path : args) {
            try (XSSFWorkbook wb = new XSSFWorkbook(OPCPackage.open(new File(path), PackageAccess.READ))) {
                realFormulas(wb, path);
            }
        }
        negativeAndEdgeCases();
        fillerRow();
        System.out.println("WP4 TOTAL failures: " + failures);
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void check(String what, boolean ok) {
        if (!ok) {
            failures++;
            System.out.println("  FAIL: " + what);
        }
    }

    private static Layout layout() {
        List<String> cols = new ArrayList<>();
        for (int i = 1; i <= 200; i++) {
            cols.add("c" + i);
        }
        List<String> rows = new ArrayList<>();
        for (int i = 1; i <= 2000; i++) {
            rows.add("r" + i);
        }
        return new Layout(cols, rows);
    }

    // ---------------------------------------------------------------------------------------------------------------

    private static void realFormulas(XSSFWorkbook wb, String label) throws Exception {
        Layout layout = layout();
        int formulas = 0;
        int identical = 0;
        int refsLexer = 0;
        int refsPoi = 0;
        int sameRow = 0;
        int otherRow = 0;
        int absolute = 0;
        int ranges = 0;
        Map<String, String> stored = new LinkedHashMap<>();
        List<Cell> cells = new ArrayList<>();
        for (int s = 0; s < wb.getNumberOfSheets(); s++) {
            for (Row row : wb.getSheetAt(s)) {
                for (Cell cell : row) {
                    if (cell.getCellType() != CellType.FORMULA) {
                        continue;
                    }
                    formulas++;
                    cells.add(cell);
                    String excel = cell.getCellFormula();
                    int rowNo = cell.getRowIndex() + 1;
                    String ids = IdFormulas.toIds(excel, rowNo, layout);
                    stored.put(s + "!" + cell.getAddress(), ids);
                    String back = IdFormulas.toExcel(ids, rowNo, layout);
                    if (back.equals(excel)) {
                        identical++;
                    } else {
                        System.out.println("  text differs at sheet#" + s + " " + cell.getAddress());
                    }
                    int lexer = IdFormulas.countReferences(excel);
                    int poi = 0;
                    Ptg[] tokens = FormulaParser.parse(excel, XSSFEvaluationWorkbook.create(wb), FormulaType.CELL, s);
                    for (Ptg t : tokens) {
                        if (t instanceof RefPtg) {
                            poi++;
                        } else if (t instanceof AreaPtg) {
                            poi += 2;
                            ranges++;
                        }
                    }
                    refsLexer += lexer;
                    refsPoi += poi;
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("@\\{([^}]*)}").matcher(ids);
                    while (m.find()) {
                        String[] parts = m.group(1).split(",", -1);
                        if (parts.length == 1 || parts.length >= 2 && parts[1].isEmpty()) {
                            sameRow++;
                        } else {
                            otherRow++;
                        }
                        if (parts.length == 3 && !parts[2].isEmpty()) {
                            absolute++;
                        }
                    }
                }
            }
        }
        System.out.println("WP4.1 [" + label.substring(label.lastIndexOf('/') + 1) + "] formulas=" + formulas
                + " text identical after ids and back=" + identical + " ; references found by our lexer=" + refsLexer
                + " by POI's tokens=" + refsPoi + " ; same-row=" + sameRow + " other-row=" + otherRow + " absolute="
                + absolute + " ranges=" + ranges);
        check("round trip text", identical == formulas);
        check("lexer and POI agree", refsLexer == refsPoi);
        if (formulas == 0) {
            return;
        }

        // Insert a column (supplier data sheet) or a row (plant sheets) in a COPY and check that the stored text is not
        // rewritten while the drawn text changes, and that every value stays what the spreadsheet program stored.
        boolean columnTest = wb.getNumberOfSheets() > 3;
        int changedStored = 0;
        int drawnChanged = 0;
        int valuesEqual = 0;
        try (XSSFWorkbook copy = new XSSFWorkbook()) {
            Layout shifted;
            int insertAt;
            if (columnTest) {
                insertAt = 7;
                shifted = layout.withColumnInserted(insertAt, "cNEW");
            } else {
                insertAt = 5;
                shifted = layout.withRowInserted(insertAt, "rNEW");
            }
            Map<Integer, Sheet> target = new LinkedHashMap<>();
            for (Cell cell : cells) {
                int s = cell.getSheet().getWorkbook().getSheetIndex(cell.getSheet());
                Sheet t = target.computeIfAbsent(s, k -> copy.createSheet("s" + k));
                // copy the values of the whole sheet once
                if (t.getPhysicalNumberOfRows() == 0) {
                    for (Row r : cell.getSheet()) {
                        for (Cell c : r) {
                            if (c.getCellType() == CellType.FORMULA || c.getCellType() == CellType.BLANK) {
                                continue;
                            }
                            int newRow = c.getRowIndex() + (!columnTest && c.getRowIndex() >= insertAt ? 1 : 0);
                            int newCol = c.getColumnIndex() + (columnTest && c.getColumnIndex() >= insertAt ? 1 : 0);
                            Row tr = t.getRow(newRow) == null ? t.createRow(newRow) : t.getRow(newRow);
                            Cell tc = tr.createCell(newCol);
                            switch (c.getCellType()) {
                                case NUMERIC -> tc.setCellValue(c.getNumericCellValue());
                                case STRING -> tc.setCellValue(c.getStringCellValue());
                                case BOOLEAN -> tc.setCellValue(c.getBooleanCellValue());
                                case ERROR -> tc.setCellErrorValue(c.getErrorCellValue());
                                default -> { }
                            }
                        }
                    }
                }
            }
            for (Cell cell : cells) {
                int s = cell.getSheet().getWorkbook().getSheetIndex(cell.getSheet());
                String key = s + "!" + cell.getAddress();
                String ids = stored.get(key);
                int oldRow = cell.getRowIndex() + 1;
                int newRowIdx = cell.getRowIndex() + (!columnTest && cell.getRowIndex() >= insertAt ? 1 : 0);
                int newColIdx = cell.getColumnIndex() + (columnTest && cell.getColumnIndex() >= insertAt ? 1 : 0);
                String drawn = IdFormulas.toExcel(ids, newRowIdx + 1, shifted);
                if (!drawn.equals(cell.getCellFormula())) {
                    drawnChanged++;
                }
                if (!stored.get(key).equals(ids)) {
                    changedStored++;
                }
                Sheet t = target.get(s);
                Row tr = t.getRow(newRowIdx) == null ? t.createRow(newRowIdx) : t.getRow(newRowIdx);
                tr.createCell(newColIdx).setCellFormula(drawn);
            }
            org.apache.poi.ss.usermodel.FormulaEvaluator ev = copy.getCreationHelper().createFormulaEvaluator();
            for (Cell cell : cells) {
                int s = cell.getSheet().getWorkbook().getSheetIndex(cell.getSheet());
                int newRowIdx = cell.getRowIndex() + (!columnTest && cell.getRowIndex() >= insertAt ? 1 : 0);
                int newColIdx = cell.getColumnIndex() + (columnTest && cell.getColumnIndex() >= insertAt ? 1 : 0);
                Cell moved = target.get(s).getRow(newRowIdx).getCell(newColIdx);
                CellValue got = ev.evaluate(moved);
                if (sameValue(cell, got)) {
                    valuesEqual++;
                }
            }
        }
        System.out.println("WP4.2 [" + (columnTest ? "a column inserted before column H" : "a row inserted inside the data")
                + "] stored formulas rewritten=" + changedStored + " ; drawn Excel-style text changed for " + drawnChanged + " of "
                + formulas + " ; values equal to the spreadsheet program's stored value=" + valuesEqual + " of " + formulas);
        check("insert rewrites nothing", changedStored == 0);
        check("values after insert", valuesEqual == formulas);
        check("drawn text changed where it must", drawnChanged > 0);
    }

    private static boolean sameValue(Cell original, CellValue got) {
        switch (original.getCachedFormulaResultType()) {
            case NUMERIC:
                return got.getCellType() == CellType.NUMERIC
                        && Math.abs(original.getNumericCellValue() - got.getNumberValue()) <= 1e-9;
            case STRING:
                return got.getCellType() == CellType.STRING && original.getStringCellValue().equals(got.getStringValue());
            case BOOLEAN:
                return got.getCellType() == CellType.BOOLEAN && original.getBooleanCellValue() == got.getBooleanValue();
            case ERROR:
                return got.getCellType() == CellType.ERROR && original.getErrorCellValue() == got.getErrorValue();
            default:
                return false;
        }
    }

    // ---------------------------------------------------------------------------------------------------------------

    private static void negativeAndEdgeCases() {
        Layout layout = layout();
        String[][] refused = {{"ZZ1+1", "column_outside_sheet"}, {"A9999999+1", "row_outside_sheet"},
            {"A0+1", "row_outside_sheet"}, {"Sheet2!A1", "character_not_allowed"}, {"[1]Sheet1!A1", "character_not_allowed"},
            {"'x'!A1", "character_not_allowed"}, {"@A1", "character_not_allowed"}, {"{1,2}", "character_not_allowed"},
            {"IF(A1=\"x", "unclosed_text"}, {"SUM(A1:A2001)", "row_outside_sheet"}};
        int refusedOk = 0;
        for (String[] c : refused) {
            try {
                IdFormulas.toIds(c[0], 1, layout);
                System.out.println("  NOT REFUSED: " + c[1]);
            } catch (Refused r) {
                if (r.key().equals(c[1])) {
                    refusedOk++;
                } else {
                    System.out.println("  refused for another reason: " + r.key() + " expected " + c[1]);
                }
            }
        }
        System.out.println("WP4.3 formulas that must be refused, with the right reason: " + refusedOk + " of " + refused.length);
        check("refusals", refusedOk == refused.length);

        String[] roundTrip = {"$A$1+1", "A$1*$B2", "SUM(D4:D9)", "SUM($D$4:$D$9)", "IF(A3=\"A1\",\"\",A3)", "LOG10(A1)+ATAN2(B2,C3)",
            "A1:C3", "ROUND(A1,2)", "1E5+A1", "0.5*A1"};
        int ok = 0;
        for (String f : roundTrip) {
            String ids = IdFormulas.toIds(f, 3, layout);
            if (IdFormulas.toExcel(ids, 3, layout).equals(f)) {
                ok++;
            } else {
                System.out.println("  round trip differs for a case");
            }
        }
        System.out.println("WP4.4 edge cases (absolute markers, ranges, a reference inside a text constant, function names"
                + " that look like references, numbers with an exponent) round trip: " + ok + " of " + roundTrip.length);
        check("edge cases", ok == roundTrip.length);

        // a total over a range keeps its first and last row identifiers when the author inserts rows
        String ids = IdFormulas.toIds("SUM(D4:D9)", 10, layout);
        String before = IdFormulas.toExcel(ids, 10, layout);
        String inside = IdFormulas.toExcel(ids, 11, layout.withRowInserted(5, "rX"));
        String atEnd = IdFormulas.toExcel(ids, 11, layout.withRowInserted(9, "rX"));
        String beforeFirst = IdFormulas.toExcel(ids, 11, layout.withRowInserted(2, "rX"));
        boolean rangeOk = before.equals("SUM(D4:D9)") && inside.equals("SUM(D4:D10)") && atEnd.equals("SUM(D4:D9)")
                && beforeFirst.equals("SUM(D5:D10)");
        System.out.println("WP4.5 total over a range, drawn after an insert inside / after the last row / before the first row:"
                + " expands / stays / moves as a spreadsheet program does = " + rangeOk);
        check("range behaviour", rangeOk);

        // identifiers named by a stored formula must exist
        boolean unknownRefused = false;
        try {
            IdFormulas.toExcel("@{cGONE}+1", 1, layout);
        } catch (Refused r) {
            unknownRefused = r.key().equals("unknown_column");
        }
        boolean removedRowRefused = false;
        try {
            IdFormulas.toExcel("@{c1,rGONE}+1", 1, layout);
        } catch (Refused r) {
            removedRowRefused = r.key().equals("unknown_row");
        }
        System.out.println("WP4.6 a stored formula naming a column or a row that no longer exists is refused: "
                + (unknownRefused && removedRowRefused));
        check("unknown ids refused", unknownRefused && removedRowRefused);
    }

    /** The pattern row of a calculated column drawn at rows the filler adds. */
    private static void fillerRow() throws Exception {
        Layout layout = layout();
        String pattern = "IF(H4=\"\",\"\",MIN(100,((I4/H4)+(0.5*(J4-I4)/H4))*100))";
        String ids = IdFormulas.toIds(pattern, 4, layout);
        int rows = 0;
        int ok = 0;
        for (int row : new int[] {4, 5, 6, 57, 500}) {
            rows++;
            String drawn = IdFormulas.toExcel(ids, row, layout);
            String expected = pattern.replace("H4", "H" + row).replace("I4", "I" + row).replace("J4", "J" + row);
            if (drawn.equals(expected)) {
                ok++;
            }
        }
        // evaluated at a filler-added row: 10 planned, 7 done, 8 target -> (7/10)+(0.5*(8-7)/10) = 0.75 -> 75
        double value;
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet s = wb.createSheet("s");
            Row r = s.createRow(56);
            r.createCell(7).setCellValue(10);
            r.createCell(8).setCellValue(7);
            r.createCell(9).setCellValue(8);
            Cell f = r.createCell(10);
            f.setCellFormula(IdFormulas.toExcel(ids, 57, layout));
            CellValue v = wb.getCreationHelper().createFormulaEvaluator().evaluate(f);
            value = v.getCellType() == CellType.NUMERIC ? v.getNumberValue() : Double.NaN;
        }
        // an empty row stays empty: the guard returns the empty text
        String blankResult;
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet s = wb.createSheet("s");
            Row r = s.createRow(57);
            Cell f = r.createCell(10);
            f.setCellFormula(IdFormulas.toExcel(ids, 58, layout));
            CellValue v = wb.getCreationHelper().createFormulaEvaluator().evaluate(f);
            blankResult = v.getCellType() == CellType.STRING ? "text:" + v.getStringValue().length() : v.getCellType().name();
        }
        System.out.println("WP4.7 the pattern of a calculated column drawn at filler-added rows: text correct " + ok + " of "
                + rows + " ; value at an added row = " + value + " (expected 75.0) ; blank row -> " + blankResult);
        check("pattern drawn per row", ok == rows);
        check("pattern value", Math.abs(value - 75.0) < 1e-9);
        check("pattern blank", blankResult.equals("text:0"));
    }
}
