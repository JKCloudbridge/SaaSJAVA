package spike;

import java.util.ArrayList;
import java.util.List;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * POC 2, WP5.3. The growing range in Java: the stored text is written once, drawn with the rows that exist, round-trips at every
 * size, and POI (drawn text, recalculated) gives the answer that follows when the filler adds rows.
 */
public final class GrowingRangeCheck {

    private GrowingRangeCheck() {
    }

    private static IdFormulas2.Layout layout(int bodyRows) {
        List<String> rows = new ArrayList<>();
        List<String> body = new ArrayList<>();
        rows.add("h1");
        for (int i = 1; i <= bodyRows; i++) {
            rows.add("b" + i);
            body.add("b" + i);
        }
        rows.add("t1");
        return new IdFormulas2.Layout(List.of("lab", "val", "tot"), rows, body);
    }

    private static double recalc(String drawn, int bodyRows, double[] values, int formulaRow) {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sh = wb.createSheet("s");
            for (int i = 0; i < bodyRows; i++) {
                Row r = sh.createRow(1 + i);
                r.createCell(1).setCellValue(values[i]);
            }
            Row t = sh.createRow(formulaRow - 1);
            Cell c = t.createCell(2);
            c.setCellFormula(drawn);
            return wb.getCreationHelper().createFormulaEvaluator().evaluate(c).getNumberValue();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    public static void main(String[] args) {
        int failures = 0;
        IdFormulas2.Layout l3 = layout(3);
        String stored = IdFormulas2.toIds("AVERAGE(B2:B4)", 5, l3);
        failures += check("stored text is the token", stored.equals("AVERAGE(@{val,*})"));
        failures += check("drawn at 3 body rows", IdFormulas2.toExcel(stored, 5, l3).equals("AVERAGE(B2:B4)"));
        IdFormulas2.Layout l5 = layout(5);
        failures += check("drawn at 5 body rows", IdFormulas2.toExcel(stored, 7, l5).equals("AVERAGE(B2:B6)"));
        failures += check("round trip at 3", IdFormulas2.toIds(IdFormulas2.toExcel(stored, 5, l3), 5, l3).equals(stored));
        failures += check("round trip at 5", IdFormulas2.toIds(IdFormulas2.toExcel(stored, 7, l5), 7, l5).equals(stored));
        failures += check("a partial range keeps its identifiers", IdFormulas2.toIds("SUM(B2:B3)", 5, l3).equals("SUM(@{val,b1}:@{val,b2})"));
        failures += check("open end drawn at 3", IdFormulas2.toExcel("SUM(@{val,b2}:@{val,*})", 5, l3).equals("SUM(B3:B4)"));
        failures += check("open end drawn at 5", IdFormulas2.toExcel("SUM(@{val,b2}:@{val,*})", 7, l5).equals("SUM(B3:B6)"));
        failures += check("open start drawn", IdFormulas2.toExcel("SUM(@{val,*}:@{val,b2})", 5, l3).equals("SUM(B2:B3)"));
        boolean refused = false;
        try {
            IdFormulas2.toIds("SUM(B2:B99)", 5, l3);
        } catch (IdFormulas.Refused e) {
            refused = e.key().equals("row_outside_sheet");
        }
        failures += check("a reference outside the sheet is refused", refused);
        // POI recalculates the drawn text for the sheet as it is now
        double three = recalc(IdFormulas2.toExcel(stored, 5, l3), 3, new double[] {2, 4, 9}, 5);
        double five = recalc(IdFormulas2.toExcel(stored, 7, l5), 5, new double[] {2, 4, 9, 10, 15}, 7);
        failures += check("POI average of 3 rows = 5", Math.abs(three - 5) < 1e-9);
        failures += check("POI average of 5 rows = 8 (rows added, stored text unchanged)", Math.abs(five - 8) < 1e-9);
        System.out.println("GROWING RANGE (Java): failures " + failures);
    }

    private static int check(String name, boolean ok) {
        if (!ok) {
            System.out.println("  FAIL " + name);
        }
        return ok ? 0 : 1;
    }
}
