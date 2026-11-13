package app.platform.sheets.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.sheets.internal.SheetGrid.RowsMode;
import app.platform.sheets.internal.SpreadsheetImporter.Format;
import app.platform.sheets.internal.formula.FormulaEvaluator;
import app.platform.sheets.internal.formula.FormulaLimits;
import app.platform.sheets.internal.formula.FormulaParser;
import app.platform.sheets.internal.formula.FormulaRefused;
import app.platform.sheets.internal.formula.FormulaTranslator;
import app.platform.sheets.internal.formula.Node;
import app.platform.sheets.internal.formula.XlError;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Reads real workbooks and writes <em>counts only</em> about their formulas to {@code
 * target/real-workbooks-formulas.txt}
 * (SH2). The files are named in the system property {@code realWorkbooks} (paths separated by a semicolon), read from
 * bytes, never opened for writing, never copied, and nothing of them is quoted: no formula, text, name or address, only
 * numbers and stable codes. For each worksheet it counts the formulas, how many come in as formulas and how many
 * as last
 * values and why, and, as a test-only path, calculates every accepted formula with the workbook's own cell values
 * as the
 * inputs and counts how many answers equal the value the spreadsheet program stored (an error it stored counts when the
 * platform gives the same error). It runs only when the property is set.
 */
class RealWorkbooksFormulaReportTest {

    private static final SheetProperties.Limits LIMITS = SheetProperties.Limits.standard();
    private static final FormulaLimits FORMULAS = FormulaLimits.DEFAULTS;

    @Test
    @EnabledIfSystemProperty(named = "realWorkbooks", matches = ".+")
    void writesTheFormulaCountsOfTheRealWorkbooks() throws IOException {
        StringBuilder report = new StringBuilder();
        int workbook = 0;
        for (String path : System.getProperty("realWorkbooks").split(";")) {
            workbook++;
            byte[] file = Files.readAllBytes(Path.of(path));
            report.append("workbook ").append(workbook).append(": ").append(file.length).append(" bytes\n");
            int worksheets = new WorkbookReader(ImportProperties.standard(), LIMITS).read(file, null).worksheets.size();
            for (int i = 0; i < worksheets; i++) {
                RawSheet raw = new WorkbookReader(ImportProperties.standard(), LIMITS).read(file, i);
                describe(report, i, raw);
                imported(report, file, i);
            }
        }
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target/real-workbooks-formulas.txt"), report.toString());
        assertThat(report).isNotEmpty();
    }

    // ---- the engine against the values the spreadsheet program stored ----

    private static void describe(StringBuilder out, int index, RawSheet raw) {
        int total = 0;
        int usable = 0;
        int refused = 0;
        Map<String, Integer> reasons = new TreeMap<>();
        Map<Long, Node> trees = new HashMap<>();
        FormulaTranslator.Layout layout = flat(raw);
        for (var row : raw.formulas.entrySet()) {
            for (var found : row.getValue().entrySet()) {
                total++;
                if (found.getValue().text() == null) {
                    reasons.merge(found.getValue().reason(), 1, Integer::sum);
                    refused++;
                    continue;
                }
                try {
                    String stored = FormulaTranslator.toIds(FormulaImport.withoutPrefixes(found.getValue().text()),
                            row.getKey() + 1, layout);
                    trees.put(((long) row.getKey() << 32) | found.getKey(), FormulaParser.parse(stored, FORMULAS));
                    usable++;
                } catch (FormulaRefused no) {
                    reasons.merge(no.reason(), 1, Integer::sum);
                    refused++;
                }
            }
        }
        int equal = 0;
        int errorsEqual = 0;
        int differ = 0;
        for (var entry : trees.entrySet()) {
            int row = (int) (entry.getKey() >> 32);
            int column = (int) (entry.getKey() & 0xffffffffL);
            Object stored = cached(raw, row, column);
            Object got = FormulaEvaluator.evaluate(entry.getValue(), "r" + (row + 1), grid(raw, trees), FORMULAS);
            if (same(stored, got)) {
                equal++;
                if (stored instanceof XlError) {
                    errorsEqual++;
                }
            } else {
                differ++;
            }
        }
        out.append("  worksheet ").append(index + 1).append(": ").append(total).append(" formulas (longest ")
                .append(raw.longestFormula).append(" characters, nesting ").append(raw.deepestFormula).append("); ")
                .append(usable).append(" pass the translator and the gate, ").append(refused)
                .append(" do not (by reason ").append(reasons).append("); calculated with the workbook's own values: ")
                .append(equal).append(" equal the stored value (").append(errorsEqual)
                .append(" of them an error the program stored), ").append(differ).append(" differ\n");
    }

    private static FormulaTranslator.Layout flat(RawSheet raw) {
        int columns = 0;
        int rows = 0;
        for (var row : raw.rows.entrySet()) {
            rows = Math.max(rows, row.getKey() + 1);
            columns = Math.max(columns, row.getValue().lastKey() + 1);
        }
        for (var row : raw.formulas.entrySet()) {
            rows = Math.max(rows, row.getKey() + 1);
            columns = Math.max(columns, row.getValue().lastKey() + 1);
        }
        for (var row : raw.typed.entrySet()) {
            rows = Math.max(rows, row.getKey() + 1);
            columns = Math.max(columns, row.getValue().lastKey() + 1);
        }
        List<String> columnIds = new ArrayList<>();
        for (int i = 1; i <= Math.max(columns, 1); i++) {
            columnIds.add("c" + i);
        }
        List<String> rowIds = new ArrayList<>();
        for (int i = 1; i <= Math.max(rows, 1); i++) {
            rowIds.add("r" + i);
        }
        return new FormulaTranslator.Layout(columnIds, rowIds, false, 0);
    }

    private static Object cached(RawSheet raw, int row, int column) {
        var held = raw.typed.get(row);
        return held == null ? null : held.get(column);
    }

    private static FormulaEvaluator.Grid grid(RawSheet raw, Map<Long, Node> formulas) {
        Map<String, Object> memo = new HashMap<>();
        return new FormulaEvaluator.Grid() {
            @Override
            public Object cell(String column, String row) {
                int c = Integer.parseInt(column.substring(1)) - 1;
                int r = Integer.parseInt(row.substring(1)) - 1;
                Node tree = formulas.get(((long) r << 32) | c);
                if (tree == null) {
                    return cached(raw, r, c);
                }
                String key = column + "|" + row;
                if (!memo.containsKey(key)) {
                    memo.put(key, XlError.REF);
                    memo.put(key, FormulaEvaluator.evaluate(tree, row, this, FORMULAS));
                }
                return memo.get(key);
            }

            @Override
            public List<Object> range(String fromColumn, String fromRow, String toColumn, String toRow) {
                int c0 = Math.min(index(fromColumn), index(toColumn));
                int c1 = Math.max(index(fromColumn), index(toColumn));
                int r0 = Math.min(index(fromRow), index(toRow));
                int r1 = Math.max(index(fromRow), index(toRow));
                List<Object> values = new ArrayList<>();
                for (int r = r0; r <= r1; r++) {
                    for (int c = c0; c <= c1; c++) {
                        values.add(cell("c" + (c + 1), "r" + (r + 1)));
                    }
                }
                return values;
            }

            @Override
            public List<String> bodyRows() {
                return List.of();
            }

            private int index(String id) {
                return Integer.parseInt(id.substring(1)) - 1;
            }
        };
    }

    private static boolean same(Object stored, Object got) {
        Object a = stored == null ? "" : stored;
        Object b = got instanceof Double d && d == 0 && (stored == null || "".equals(stored)) ? "" : got;
        if (a instanceof Double x && b instanceof Double y) {
            return Math.abs(x - y) <= 1e-9 * Math.max(1, Math.abs(x));
        }
        return a.equals(b);
    }

    // ---- what the upload makes of the worksheet ----

    private static void imported(StringBuilder out, byte[] file, int index) {
        SpreadsheetImporter importer = new SpreadsheetImporter(ImportProperties.standard(),
                new SheetProperties(LIMITS, 4096, new SheetProperties.FormatTwo(true)),
                new SheetGridValidator(LIMITS, new SheetSchemas(), true));
        for (int dimension : new int[] {2, 1}) {
            try {
                var result = importer.read(file, Format.XLSX, new SheetImportBuilder.Options(null, dimension,
                        dimension == 2 ? RowsMode.FIXED : RowsMode.REPEATABLE, index, true));
                var tab = result.checked().document().sheets().get(0);
                Map<String, Integer> notes = new TreeMap<>();
                result.report().notes().forEach(entry -> {
                    if (entry.note().code().startsWith("formulas")) {
                        notes.put(entry.note().code(), entry.count());
                    }
                });
                out.append("    upload as ").append(dimension == 2 ? "named rows" : "rows added by the filler")
                        .append(": ").append(tab.formulas().size()).append(" formula cells, ")
                        .append(tab.columnFormulas().size()).append(" calculated columns; notes ").append(notes)
                        .append('\n');
            } catch (ImportRefusal refusal) {
                out.append("    upload as ").append(dimension == 2 ? "named rows" : "rows added by the filler")
                        .append(": refused (").append(refusal.code()).append(")\n");
            }
        }
    }
}
