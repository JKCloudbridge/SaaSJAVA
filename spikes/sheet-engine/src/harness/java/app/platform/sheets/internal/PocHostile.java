package app.platform.sheets.internal;

import app.platform.sheets.internal.SpreadsheetImporter.Format;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import spike.MiniXlsx;
import spike.PoiSafe;
import spike.PoiSafe.Limits;
import spike.PoiSafe.Outcome;
import spike.PoiSafe.Result;

/**
 * WP1.4 harness. Lives in the package of the reader only to reach its package-private test helpers; it changes nothing under
 * platform-app. Part A re-runs the platform's own corpus against OUR reader. Part B builds a valid-package twin of every class of
 * hostile file and shows (1) what OUR reader and guards say, (2) what POI alone does under its own limits, (3) what POI does
 * behind a zip-level pre-scan of ours. Only stable reason keys and counts are printed, never text of a file.
 */
public final class PocHostile {

    private PocHostile() {
    }

    private record Case(String name, byte[] bytes) {
    }

    public static void main(String[] args) throws Exception {
        Path scratch = Path.of(args[0]);
        Limits limits = Limits.standard();
        PoiSafe.configurePoi(limits);
        partA();
        partB(scratch, limits);
        xxe(scratch);
        formulaGate();
        parserWithoutTheGuard();
    }

    /** What POI's parser does with a deep or very long formula when the text bounds are NOT applied first. */
    private static void parserWithoutTheGuard() throws Exception {
        try (org.apache.poi.xssf.usermodel.XSSFWorkbook wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook()) {
            wb.createSheet("Sheet1");
            for (int depth : new int[] {32, 200, 1_000, 5_000}) {
                probe(wb, "nesting " + depth, "(".repeat(depth) + "1" + ")".repeat(depth));
            }
            for (int terms : new int[] {2_000, 50_000}) {
                probe(wb, "long sum of " + terms + " terms", "1+".repeat(terms) + "1");
            }
        }
    }

    private static void probe(org.apache.poi.xssf.usermodel.XSSFWorkbook wb, String what, String formula) {
        long t0 = System.nanoTime();
        String outcome;
        try {
            org.apache.poi.ss.formula.FormulaParser.parse(formula,
                    org.apache.poi.xssf.usermodel.XSSFEvaluationWorkbook.create(wb),
                    org.apache.poi.ss.formula.FormulaType.CELL, 0);
            outcome = "parsed";
        } catch (StackOverflowError e) {
            outcome = "STACK OVERFLOW";
        } catch (Throwable e) {
            outcome = "refused " + e.getClass().getSimpleName();
        }
        System.out.println("  raw POI parser, " + what + ": " + outcome + " in " + (System.nanoTime() - t0) / 1_000_000
                + " ms; through our gate: " + (spike.Whitelist.check(wb, 0, formula).isEmpty() ? "accepted"
                        : "refused " + spike.Whitelist.check(wb, 0, formula)));
    }

    // ---- Part A: the platform's corpus against our reader ----

    private static void partA() throws Exception {
        Method made = SpreadsheetCorpusTest.class.getDeclaredMethod("made", String.class);
        made.setAccessible(true);
        Class<?> madeType = Class.forName("app.platform.sheets.internal.SpreadsheetCorpusTest$Made");
        Method bytesOf = madeType.getDeclaredMethod("bytes");
        Method settingsOf = madeType.getDeclaredMethod("settings");
        bytesOf.setAccessible(true);
        settingsOf.setAccessible(true);
        int lines = 0;
        int same = 0;
        for (SpreadsheetCorpusTest.Construct k : SpreadsheetCorpusTest.constructs()) {
            lines++;
            Object m = made.invoke(null, k.generator());
            String got = ours((byte[]) bytesOf.invoke(m), (ImportProperties) settingsOf.invoke(m), k.format());
            if (got.equals("refused:" + k.expected())) {
                same++;
            } else {
                System.out.println("  corpus line " + k.line() + " (" + k.generator() + "): expected refused:" + k.expected()
                        + " got " + got);
            }
        }
        System.out.println("PART A  platform corpus against our reader: lines=" + lines + " refused with the expected code="
                + same);
    }

    private static String ours(byte[] bytes, ImportProperties settings, String format) {
        try {
            SpreadsheetImporterTest.importer(settings).read(bytes, Format.of(format).orElseThrow(),
                    SpreadsheetImporterTest.NAMED_ROWS);
            return "ACCEPTED";
        } catch (ImportRefusal r) {
            return "refused:" + r.code();
        } catch (Throwable t) {
            return "ERROR:" + t.getClass().getSimpleName();
        }
    }

    // ---- Part B: valid-package twins ----

    private static void partB(Path scratch, Limits limits) throws Exception {
        List<Case> cases = new ArrayList<>();
        cases.add(new Case("sanity: plain valid workbook", plain().bytes()));
        cases.add(new Case("sanity: the plant workbook (real)", Files.readAllBytes(scratch.resolve("plant.xlsx"))));
        cases.add(new Case("sanity: the supplier workbook (real)", Files.readAllBytes(scratch.resolve("spm.xlsx"))));
        cases.add(new Case("macro: VBA project part", plain().part("xl/vbaProject.bin", new byte[] {1, 2, 3})
                .override("/xl/vbaProject.bin", "application/vnd.ms-office.vbaProject").bytes()));
        cases.add(new Case("macro: macro-enabled content type", plain().macroEnabled().bytes()));
        cases.add(new Case("bomb: sheet part 25 MB unpacked", plain().prefix("<!--" + "x".repeat(25_000_000) + "-->")
                .bytes()));
        cases.add(new Case("bomb: skipped part 25 MB", plain().part("xl/media/big.bin", new byte[25_000_000]).bytes()));
        MiniXlsx many = plain();
        for (int i = 0; i < 6; i++) {
            many.part("xl/media/m" + i + ".bin", new byte[19_000_000]);
        }
        cases.add(new Case("bomb: six parts of 19 MB (114 MB together)", many.bytes()));
        MiniXlsx parts = plain();
        for (int i = 0; i < 101; i++) {
            parts.part("xl/media/p" + i + ".bin", new byte[] {1});
        }
        cases.add(new Case("flood: 101 extra parts", parts.bytes()));
        byte[] dup = plain().part("xl/media/aaa.bin", new byte[] {1}).part("xl/media/bbb.bin", new byte[] {2}).bytes();
        patch(dup, "bbb.bin", "aaa.bin");
        cases.add(new Case("advisory: two parts with the same name", dup));
        cases.add(new Case("path: part name with ../", plain().part("../../evil.xml", new byte[] {1}).bytes()));
        cases.add(new Case("formula: 100,000 characters", plain().row(1, MiniXlsx.text("A1", "h"),
                MiniXlsx.formula("B1", "1+".repeat(50_000) + "1", "1")).bytes()));
        cases.add(new Case("formula: nesting 5,000 deep", plain().row(1, MiniXlsx.text("A1", "h"),
                MiniXlsx.formula("B1", "(".repeat(5_000) + "1" + ")".repeat(5_000), "1")).bytes()));
        cases.add(new Case("formula: external workbook reference", plain().row(1, MiniXlsx.text("A1", "h"),
                MiniXlsx.formula("B1", "[1]Sheet1!A1", "0")).bytes()));
        StringBuilder laughs = new StringBuilder("<?xml version=\"1.0\"?><!DOCTYPE lolz [<!ENTITY lol0 \"lol\">");
        for (int i = 1; i <= 9; i++) {
            laughs.append("<!ENTITY lol").append(i).append(" \"").append(("&lol" + (i - 1) + ";").repeat(10)).append("\">");
        }
        laughs.append("]>");
        cases.add(new Case("xml: entity expansion (billion laughs)", plain().prefix(laughs.toString())
                .row(1, "<c r=\"A1\" t=\"inlineStr\"><is><t>&lol9;</t></is></c>").bytes()));
        cases.add(new Case("xml: external entity (XXE)", xxeFile(scratch.resolve("xxe-secret.txt"))));
        cases.add(new Case("size: one cell at XFD1048576", plain().row(1048576, MiniXlsx.text("XFD1048576", "x")).bytes()));
        cases.add(new Case("size: shared string of 12 MB", plain().sharedStrings("<sst xmlns=\"" + MiniXlsx.MAIN
                + "\"><si><t>" + "x".repeat(12_000_000) + "</t></si></sst>")
                .row(1, "<c r=\"A1\" t=\"s\"><v>0</v></c>").bytes()));
        cases.add(new Case("size: 200,000 cell formats", plain().styles("<styleSheet xmlns=\"" + MiniXlsx.MAIN
                + "\"><cellXfs count=\"200000\">" + "<xf/>".repeat(200_000) + "</cellXfs></styleSheet>").bytes()));
        MiniXlsx merges = plain();
        for (int i = 0; i < 2_001; i++) {
            merges.merge("A" + (2 + i) + ":B" + (2 + i));
        }
        cases.add(new Case("count: 2,001 merged areas (limit 2,000)", merges.bytes()));
        MiniXlsx merges300 = plain();
        for (int i = 0; i < 300_000; i++) {
            merges300.merge("A" + (2 + i) + ":B" + (2 + i));
        }
        cases.add(new Case("count: 300,000 merged areas", merges300.bytes()));
        cases.add(new Case("count: 250,000 text cells (limit 200,000)", cellsFile(25_000, 10, true)));
        cases.add(new Case("count: 1.5 million empty cells (ratio bomb)", cellsFile(15_000, 100, false)));

        System.out.printf("%-48s | %-26s | %-42s | %-40s%n", "case", "our reader", "POI alone", "POI behind zip pre-scan");
        int oursRefused = 0;
        int oursAccepted = 0;
        int acceptedThenBad = 0;
        int poiAloneBad = 0;
        for (Case k : cases) {
            String ours = ours(k.bytes(), ImportProperties.standard(), "xlsx");
            Result alone = PoiSafe.open(k.bytes(), limits, false);
            Result behind = PoiSafe.open(k.bytes(), limits, true);
            System.out.printf("%-48s | %-26s | %-42s | %-40s%n", k.name(), ours, show(alone), show(behind));
            if (ours.startsWith("refused")) {
                oursRefused++;
            } else if (ours.equals("ACCEPTED")) {
                oursAccepted++;
                if (bad(behind)) {
                    acceptedThenBad++;
                }
            }
            if (bad(alone)) {
                poiAloneBad++;
            }
        }
        System.out.println("PART B  twins=" + cases.size() + " refused by our reader=" + oursRefused + " accepted by our reader="
                + oursAccepted + " ; of the accepted, POI behind the pre-scan went wrong (memory, time, stack, other)="
                + acceptedThenBad + " ; POI alone went wrong on " + poiAloneBad + " files");
    }

    private static boolean bad(Result r) {
        return r.outcome() == Outcome.OUT_OF_MEMORY || r.outcome() == Outcome.STACK_OVERFLOW
                || r.outcome() == Outcome.TIMEOUT || r.outcome() == Outcome.OTHER_ERROR;
    }

    private static String show(Result r) {
        return r.outcome().name().replace("REFUSED_BY_", "refused-") + ":" + r.reason() + " " + r.millis() + "ms"
                + (r.outcome() == Outcome.ACCEPTED ? " sheets=" + r.sheets() + " hidden=" + r.hiddenSheets() : "");
    }

    private static MiniXlsx plain() {
        return new MiniXlsx().row(1, MiniXlsx.text("A1", "h"), MiniXlsx.text("B1", "i"))
                .row(2, MiniXlsx.number("A2", "1"), MiniXlsx.number("B2", "2"));
    }

    private static byte[] cellsFile(int rows, int columns, boolean text) {
        MiniXlsx f = new MiniXlsx();
        for (int row = 1; row <= rows; row++) {
            String[] cells = new String[columns];
            for (int c = 0; c < columns; c++) {
                String ref = SheetImportBuilder.letters(c) + row;
                cells[c] = text ? MiniXlsx.text(ref, "x") : "<c r=\"" + ref + "\" s=\"0\"/>";
            }
            f.row(row, cells);
        }
        return f.bytes();
    }

    private static byte[] xxeFile(Path secret) {
        String prefix = "<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e SYSTEM \"" + secret.toUri() + "\">]>";
        return plain().prefix(prefix).row(3, "<c r=\"A3\" t=\"inlineStr\"><is><t>&e;</t></is></c>").bytes();
    }

    private static void patch(byte[] bytes, String from, String to) {
        byte[] a = from.getBytes(StandardCharsets.ISO_8859_1);
        byte[] b = to.getBytes(StandardCharsets.ISO_8859_1);
        for (int i = 0; i + a.length <= bytes.length; i++) {
            boolean same = true;
            for (int j = 0; j < a.length && same; j++) {
                same = bytes[i + j] == a[j];
            }
            if (same) {
                System.arraycopy(b, 0, bytes, i, b.length);
            }
        }
    }

    /** Does an external entity ever reach a cell? Opens the file with POI and looks for the marker. */
    private static void xxe(Path scratch) throws Exception {
        Files.writeString(scratch.resolve("xxe-secret.txt"), "XXE-SECRET-MARKER");
        byte[] file = xxeFile(scratch.resolve("xxe-secret.txt"));
        boolean leaked = false;
        String result;
        try (org.apache.poi.xssf.usermodel.XSSFWorkbook wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook(
                new java.io.ByteArrayInputStream(file))) {
            org.apache.poi.ss.usermodel.Row row = wb.getSheetAt(0).getRow(2);
            org.apache.poi.ss.usermodel.Cell cell = row == null ? null : row.getCell(0);
            String text = cell == null ? "" : cell.getStringCellValue();
            leaked = text.contains("XXE-SECRET-MARKER");
            result = "opened, cell " + (text.isEmpty() ? "empty" : "has " + text.length() + " characters");
        } catch (Exception e) {
            result = "refused " + e.getClass().getSimpleName();
        }
        System.out.println("XXE read with POI: " + result + "; secret reached the cell: " + leaked);
    }

    /** Formulas that must be refused by the whitelist, and the ones that must pass. */
    private static void formulaGate() throws Exception {
        String[] hostile = {"INDIRECT(\"A1\")", "WEBSERVICE(\"http://example.invalid/\")",
            "HYPERLINK(\"http://example.invalid/\",\"x\")", "EXEC(\"calc\")", "OFFSET(A1,1,1)", "NOW()", "RAND()",
            "SUMPRODUCT(A1:A3,B1:B3)", "TEXT(A1,\"0\")", "VLOOKUP(A1,A1:B3,2,FALSE)", "[1]Sheet1!A1", "Sheet2!A1",
            "Defined", "cmd|' /C calc'!A0", "'file:///C:/x.xlsx'!A1", "SUM(A1:A3)+INDIRECT(\"B1\")", "A1:A3 B1:B3", "\"a\"&\"b\"", "SUM((A1,B1))",
            "(" .repeat(40) + "1" + ")".repeat(40), "1+".repeat(3000) + "1"};
        String[] good = {"SUM(A1:A3)", "IF(A1=\"\",\"\",MIN(100,A1*2))", "ROUND(A1,2)", "AND(A1>0,B1<5)",
            "ISBLANK(A1)", "MAX(A1,B1)", "OR(A1,B1)"};
        int refused = 0;
        List<String> missed = new ArrayList<>();
        try (org.apache.poi.xssf.usermodel.XSSFWorkbook wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook()) {
            wb.createSheet("Sheet1");
            wb.createSheet("Sheet2");
            for (int i = 0; i < hostile.length; i++) {
                if (spike.Whitelist.check(wb, 0, hostile[i]).isEmpty()) {
                    missed.add("#" + i);
                } else {
                    refused++;
                }
            }
            int passed = 0;
            for (String f : good) {
                if (spike.Whitelist.check(wb, 0, f).isEmpty()) {
                    passed++;
                }
            }
            System.out.println("FORMULA WHITELIST  hostile refused " + refused + " of " + hostile.length + " (missed "
                    + missed + "); good accepted " + passed + " of " + good.length);
        }
    }
}
