package app.platform.sheets.internal;

import app.platform.sheets.internal.SheetDocument.Tab;
import app.platform.sheets.internal.SheetGrid.RowsMode;
import app.platform.sheets.internal.SpreadsheetImporter.Format;
import app.platform.sheets.internal.SpreadsheetImporter.Imported;
import app.platform.sheets.internal.WorkbookWriter.Written;
import app.platform.sheets.internal.formula.FormulaLimits;
import app.platform.sheets.internal.formula.FormulaTranslator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The round trip of SH3 for the tests: a workbook is uploaded into a sheet of format version 2 (switch on), the sheet
 * is written as a download, the download is uploaded again, and what a person would call the sheet is compared by
 * position: the text, the look, the merged areas, the widths, the formulas (as spreadsheet text, so that the random
 * identifiers do not matter), the conditional colours, the frozen pane and the page set-up. Counts and positions only;
 * nothing of a cell is ever printed.
 */
final class RoundTripSupport {

    private static final SheetProperties.Limits LIMITS = SheetProperties.Limits.standard();
    static final String FOOTER = "F/MR/16  Rev No.'00'";

    private RoundTripSupport() {
    }

    /** The sheet as a person sees it, by position. */
    record Snapshot(List<String> columns, Map<String, String> texts, Map<String, SheetLook> looks,
            List<String> merges, List<String> widths, Map<String, String> formulas, List<String> conditions,
            String freeze, String print, int links) {

        /** Prints counts only. */
        @Override
        public String toString() {
            return "Snapshot[columns=" + columns.size() + ", texts=" + texts.size() + ", looks=" + looks.size()
                    + ", merges=" + merges.size() + ", formulas=" + formulas.size() + ", conditions="
                    + conditions.size() + ", links=" + links + "]";
        }
    }

    /** What one trip gave. */
    record Trip(Snapshot before, Snapshot after, Written written, byte[] file, int downloadBytes, long millis) {

        @Override
        public String toString() {
            return "Trip[" + downloadBytes + " bytes in " + millis + " ms]";
        }
    }

    static SpreadsheetImporter importer() {
        return new SpreadsheetImporter(ImportProperties.standard(),
                new SheetProperties(LIMITS, 4096, new SheetProperties.FormatTwo(true)),
                new SheetGridValidator(LIMITS, new SheetSchemas(), true));
    }

    static Imported upload(byte[] file, SheetImportBuilder.Options options) {
        return importer().read(file, Format.XLSX, options);
    }

    /** Upload, download, upload again. */
    static Trip trip(byte[] original, int dimension, RowsMode mode, Integer worksheet) {
        Imported first = upload(original, new SheetImportBuilder.Options(null, dimension, mode, worksheet, true));
        Tab tab = first.checked().document().sheets().get(0);
        long begun = System.nanoTime();
        SheetCalculator.Outcome answers = tab.formulas().isEmpty() && tab.columnFormulas().isEmpty() ? null
                : SheetCalculator.calculate(tab, dimension == 1
                        ? SheetCalculator.Inputs.emptyRows(WorkbookWriter.SAMPLE_ROWS)
                        : SheetCalculator.Inputs.none(), FormulaLimits.DEFAULTS);
        WorkbookWriter writer = new WorkbookWriter(ExportProperties.standard());
        Written written = writer.write(List.of(new WorkbookWriter.Page(tab.title(), tab.body(), tab, answers, FOOTER,
                false)), DefaultSheetExports.description(0));
        long millis = (System.nanoTime() - begun) / 1_000_000;
        Imported second = upload(written.bytes(), new SheetImportBuilder.Options(true, dimension, mode, null, true));
        return new Trip(snapshot(tab), snapshot(second.checked().document().sheets().get(0)), written,
                written.bytes(), written.bytes().length, millis);
    }

    static Snapshot snapshot(Tab tab) {
        SheetGrid grid = tab.body();
        List<String> rowIds = new ArrayList<>();
        grid.headerRows().forEach(line -> rowIds.add(line.id()));
        grid.rows().forEach(line -> rowIds.add(line.id()));
        List<String> columnIds = grid.columns().stream().map(SheetGrid.Column::id).toList();
        Map<String, String> texts = new TreeMap<>();
        Map<String, SheetLook> looks = new TreeMap<>();
        grid.cells().forEach((key, cell) -> {
            String at = place(key, rowIds, columnIds);
            if (cell.value() != null) {
                texts.put(at, cell.value().toString());
            }
        });
        // The page's own font at its own size says nothing: a file always states it, so it is not a look.
        SheetLook pageDefault = new SheetLook("SANS_HUMANIST", 11, null, null, false, null, null, null);
        tab.looks().forEach((key, look) -> {
            if (!pageDefault.equals(look)) {
                looks.put(place(key, rowIds, columnIds), look);
            }
        });
        List<String> merges = new ArrayList<>();
        grid.merges().forEach(merge -> merges.add(place(merge.from(), rowIds, columnIds) + ">"
                + place(merge.to(), rowIds, columnIds)));
        // A cell under a merged area is not part of the sheet as a person sees it (the area is one cell).
        for (SheetGrid.Merge merge : grid.merges()) {
            int[] a = corner(merge.from(), rowIds, columnIds);
            int[] b = corner(merge.to(), rowIds, columnIds);
            for (int r = a[0]; r <= b[0]; r++) {
                for (int c = a[1]; c <= b[1]; c++) {
                    if (r != a[0] || c != a[1]) {
                        looks.remove(r + "," + c);
                    }
                }
            }
        }
        List<String> widths = new ArrayList<>();
        // A column with no width is written at the screen's default width, so it comes back with that width.
        grid.columns().forEach(column -> widths.add(column.width() == null ? "120" : column.width().toString()));
        FormulaTranslator.Layout layout = new FormulaTranslator.Layout(columnIds, rowIds, grid.dimension() == 1,
                WorkbookWriter.SAMPLE_ROWS);
        Map<String, String> formulas = new TreeMap<>();
        tab.formulas().forEach((key, stored) -> formulas.put(place(key, rowIds, columnIds), FormulaTranslator
                .toExcel(stored, rowIds.indexOf(key.substring(0, key.indexOf(':'))) + 1, layout)));
        tab.columnFormulas().forEach((column, stored) -> formulas.put("column " + columnIds.indexOf(column),
                FormulaTranslator.toExcel(stored, rowIds.size() + 1, layout)));
        List<String> conditions = new ArrayList<>();
        tab.conditions().forEach(c -> conditions.add(place(c.from(), rowIds, columnIds) + ">" + place(c.to(), rowIds,
                columnIds) + " " + c.operator() + " " + c.value() + " " + c.value2() + " " + c.fill() + " "
                + c.colour()));
        String freeze = tab.freeze() == null || tab.freeze().isNone() ? "none" : tab.freeze().rows() + "/"
                + tab.freeze().columns();
        String print = tab.print() == null ? null : tab.print().paper() + " " + tab.print().orientation() + " "
                + tab.print().fitToWidth();
        return new Snapshot(grid.columns().stream().map(SheetGrid.Column::label).toList(), texts, looks, merges,
                widths, formulas, conditions, freeze, print, tab.links().size());
    }

    private static int[] corner(String key, List<String> rowIds, List<String> columnIds) {
        int colon = key.indexOf(':');
        return new int[] {rowIds.indexOf(key.substring(0, colon)), columnIds.indexOf(key.substring(colon + 1))};
    }

    private static String place(String key, List<String> rowIds, List<String> columnIds) {
        int colon = key.indexOf(':');
        return rowIds.indexOf(key.substring(0, colon)) + "," + columnIds.indexOf(key.substring(colon + 1));
    }
}
