package spike;

import java.util.ArrayList;
import java.util.List;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * POC 2, WP5.4. The 21 hostile formulas of POC 1 and the 7 good ones, run again through the larger gate, plus cases for every new
 * function and for the two narrowed forms. One of the 21 (TEXT with a listed format) is now a permitted form by decision and is
 * counted as such, not hidden.
 */
public final class GateCheck2 {

    private GateCheck2() {
    }

    public static void main(String[] args) throws Exception {
        String[] old21 = {"INDIRECT(\"A1\")", "WEBSERVICE(\"http://example.invalid/\")", "HYPERLINK(\"http://example.invalid/\",\"x\")",
            "EXEC(\"calc\")", "OFFSET(A1,1,1)", "NOW()", "RAND()", "SUMPRODUCT(A1:A3,B1:B3)", "TEXT(A1,\"0\")",
            "VLOOKUP(A1,A1:B3,2,FALSE)", "[1]Sheet1!A1", "Sheet2!A1", "Defined", "cmd|' /C calc'!A0", "'file:///C:/x.xlsx'!A1",
            "SUM(A1:A3)+INDIRECT(\"B1\")", "A1:A3 B1:B3", "\"a\"&\"b\"", "SUM((A1,B1))", "(".repeat(40) + "1" + ")".repeat(40),
            "1+".repeat(3000) + "1"};
        String[] added = {
            "COUNT(A1+1)", "COUNT(1,2)", "COUNT(A1,\"x\")", "TEXT(A1,\"dd/mm/yyyy\")", "TEXT(A1,B1)", "TEXT(A1,\"0.00\")+INDIRECT(\"A1\")",
            "SUMIF(A1:A3,\">1\",INDIRECT(\"B1:B3\"))", "IFERROR(INDIRECT(\"A1\"),0)", "N(A1)", "CONCATENATE(A1,HYPERLINK(\"x\"))",
            "LEFT(A1,WEBSERVICE(\"x\"))", "AVERAGEIF(A1:A3,\"x\",Sheet2!B1:B3)", "ROUND(SUMPRODUCT(A1:A2,B1:B2),0)",
            "IFS(NOW()>0,1)", "SWITCH(RAND(),1,2)", "LARGE(OFFSET(A1,0,0,3,1),1)", "RANK(A1,[1]Sheet1!A1:A3)",
            "VALUE(INFO(\"x\"))", "MEDIAN(A1:A3 B1:B3)", "TODAY()", "RANDBETWEEN(1,2)", "CELL(\"filename\")", "ENCODEURL(A1)",
            "FILTERXML(A1,\"//a\")", "IFS+1", "Sheet2!IFERROR(1,2)", "MyName*2", "LEN(A1)&LEN(B1)", "STDEV(Sheet2!A1:A3)", "MOD(A1,3)+EVALUATE(\"1\")",
            "SUM(" + "A1,".repeat(2000) + "A1)", "IF(".repeat(33) + "1" + ",1,1)".repeat(33)};
        String[] good = {"SUM(A1:A3)", "IF(A1=\"\",\"\",MIN(100,A1*2))", "ROUND(A1,2)", "AND(A1>0,B1<5)", "ISBLANK(A1)", "MAX(A1,B1)",
            "OR(A1,B1)", "TEXT(A1,\"0\")", "TEXT(A1,\"0.00%\")", "COUNT(A1:A5)", "COUNT(A1,B1)", "ABS(A1)", "MOD(A1,3)", "INT(A1)",
            "ROUNDUP(A1,1)", "ROUNDDOWN(A1,1)", "SQRT(A1)", "POWER(A1,2)", "PRODUCT(A1:A3)", "IFERROR(A1/B1,0)", "NOT(A1)", "ISNUMBER(A1)",
            "ISTEXT(A1)", "SUMIF(A1:A3,\">1\")", "SUMIF(A1:A3,\">1\",B1:B3)", "COUNTIF(A1:A3,\"x\")", "AVERAGEIF(A1:A3,\">1\")",
            "IFS(A1>1,\"a\",TRUE,\"b\")", "SWITCH(A1,1,\"x\",\"y\")", "AVERAGE(A1:A5)", "MEDIAN(A1:A5)", "MODE(A1:A5)", "STDEV(A1:A5)",
            "VAR(A1:A5)", "COUNTA(A1:A5)", "LARGE(A1:A5,2)", "SMALL(A1:A5,2)", "RANK(A1,A1:A5)", "VALUE(\"12\")",
            "CONCATENATE(A1,\"-\",B1)", "LEFT(A1,2)", "RIGHT(A1,2)", "LEN(A1)", "UPPER(A1)", "TRIM(A1)"};
        int refused = 0;
        List<String> missed = new ArrayList<>();
        int movedToAllowed = 0;
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            wb.createSheet("Sheet1");
            wb.createSheet("Sheet2");
            for (int i = 0; i < old21.length; i++) {
                boolean ok = Whitelist2.check(wb, 0, old21[i]).isEmpty();
                if (!ok) {
                    refused++;
                } else if (old21[i].startsWith("TEXT(")) {
                    movedToAllowed++;
                } else {
                    missed.add("old#" + i);
                }
            }
            int refusedNew = 0;
            List<String> missedNew = new ArrayList<>();
            for (int i = 0; i < added.length; i++) {
                if (Whitelist2.check(wb, 0, added[i]).isEmpty()) {
                    missedNew.add("new#" + i);
                } else {
                    refusedNew++;
                }
            }
            int accepted = 0;
            List<String> rejectedGood = new ArrayList<>();
            for (String f : good) {
                if (Whitelist2.check(wb, 0, f).isEmpty()) {
                    accepted++;
                } else {
                    rejectedGood.add(f.substring(0, Math.min(12, f.length())) + " " + Whitelist2.check(wb, 0, f));
                }
            }
            System.out.println("GATE 2  the 21 of POC 1: refused " + refused + " of " + old21.length + " (TEXT with a listed format is now a permitted form: "
                    + movedToAllowed + "); missed " + missed);
            System.out.println("GATE 2  new hostile cases: refused " + refusedNew + " of " + added.length + " (missed " + missedNew + ")");
            System.out.println("GATE 2  good formulas accepted " + accepted + " of " + good.length + " (rejected " + rejectedGood + ")");
        }
    }
}
