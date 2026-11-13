package app.platform.sheets.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.sheets.internal.SheetGrid.RowsMode;
import app.platform.sheets.internal.SpreadsheetImporter.Format;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Reads real workbooks and writes <em>counts only</em> to {@code target/real-workbooks-report.txt} (SH1B). The files
 * are named in the system property {@code realWorkbooks} (paths separated by a semicolon), read from bytes, never
 * opened
 * for writing, never copied, and nothing of them is quoted: no title, text, name, font or address, only numbers and the
 * stable codes of the notes. It runs only when the property is set.
 */
class RealWorkbooksReportTest {

    private static final SheetProperties.Limits LIMITS = SheetProperties.Limits.standard();

    @Test
    @EnabledIfSystemProperty(named = "realWorkbooks", matches = ".+")
    void writesTheCountsOfTheRealWorkbooks() throws IOException {
        StringBuilder report = new StringBuilder();
        SpreadsheetImporter importer = new SpreadsheetImporter(ImportProperties.standard(),
                new SheetProperties(LIMITS, 4096, new SheetProperties.FormatTwo(true)),
                new SheetGridValidator(LIMITS, new SheetSchemas(), true));
        int workbook = 0;
        for (String path : System.getProperty("realWorkbooks").split(";")) {
            workbook++;
            byte[] file = Files.readAllBytes(Path.of(path));
            report.append("workbook ").append(workbook).append(": ").append(file.length).append(" bytes\n");
            int worksheets = new WorkbookReader(ImportProperties.standard(), LIMITS).read(file, null).worksheets.size();
            for (int i = 0; i < worksheets; i++) {
                long started = System.nanoTime();
                RawSheet raw = new WorkbookReader(ImportProperties.standard(), LIMITS).read(file, i);
                long millis = (System.nanoTime() - started) / 1_000_000;
                describe(report, i, raw, millis);
                imported(report, importer, file, i);
            }
        }
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target/real-workbooks-report.txt"), report.toString());
        assertThat(report).isNotEmpty();
    }

    private static void describe(StringBuilder out, int index, RawSheet raw, long millis) {
        int text = 0;
        Set<String> fonts = new HashSet<>();
        Set<String> fills = new HashSet<>();
        Set<String> colours = new HashSet<>();
        int longest = 0;
        for (var row : raw.rows.values()) {
            for (RawSheet.Cell cell : row.values()) {
                text++;
                longest = Math.max(longest, cell.text().length());
                collect(cell.format(), fonts, fills, colours);
            }
        }
        for (var row : raw.blankLooks.values()) {
            for (StyleBook.Format format : row.values()) {
                collect(format, fonts, fills, colours);
            }
        }
        int bordered = 0;
        for (var columns : raw.bordered.values()) {
            bordered += columns.cardinality();
        }
        out.append("  worksheet ").append(index + 1).append(" (declared ").append(raw.worksheets.get(index).rows())
                .append(" rows, ").append(raw.worksheets.get(index).columns()).append(" columns): read in ")
                .append(millis).append(" ms; ").append(text).append(" cells with text (longest ").append(longest)
                .append(" characters); ").append(bordered).append(" bordered blank cells; ").append(raw.merges.size())
                .append(" merged areas; ").append(fonts.size()).append(" font keys, ").append(fills.size())
                .append(" fills, ").append(colours.size()).append(" text colours; frozen ").append(raw.freezeRows)
                .append(" rows ").append(raw.freezeColumns).append(" columns; ").append(raw.rules.size())
                .append(" conditional rules; longest formula ").append(raw.longestFormula).append(" characters, ")
                .append("nesting ").append(raw.deepestFormula).append("; check boxes placed ").append(raw.boxesPlaced)
                .append(" in ").append(raw.yesNo.size()).append(" cells (").append(raw.boxPairs).append(" pairs), ")
                .append(raw.unplacedBoxes.size()).append(" not placed; embedded files at ")
                .append(raw.embeddedAt.size()).append(" places; links kept ").append(raw.links.size()).append('\n');
        int withText = 0;
        int covered = 0;
        int inFirstRow = 0;
        int firstRow = raw.isEmpty() ? 0 : raw.extent().top();
        for (RawSheet.YesNo cell : raw.yesNo) {
            if (raw.rows.containsKey(cell.row()) && raw.rows.get(cell.row()).containsKey(cell.column())) {
                withText++;
            }
            for (RawSheet.Area area : raw.merges) {
                if (cell.row() >= area.top() && cell.row() <= area.bottom() && cell.column() >= area.left()
                        && cell.column() <= area.right()
                        && (cell.row() != area.top() || cell.column() != area.left())) {
                    covered++;
                    break;
                }
            }
            if (cell.row() == firstRow) {
                inFirstRow++;
            }
        }
        int onlyYesNo = 0;
        int shortText = 0;
        int longText = 0;
        for (RawSheet.YesNo cell : raw.yesNo) {
            var held = raw.rows.get(cell.row());
            String heldText = held == null || held.get(cell.column()) == null ? "" : held.get(cell.column()).text();
            String bare = heldText.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z]", "");
            if (bare.equals("yes") || bare.equals("no") || bare.equals("yesno") || bare.equals("noyes")) {
                onlyYesNo++;
            } else if (heldText.length() <= 30) {
                shortText++;
            } else {
                longText++;
            }
        }
        out.append("    held_text in them: ").append(onlyYesNo).append(" only Yes/No words, ").append(shortText)
                .append(" up to 30 characters, ").append(longText).append(" longer\n");
        out.append("    yes/no cells: ").append(withText).append(", yes/no cells on text, ").append(covered)
                .append(" under a merge, ")
                .append(inFirstRow).append(" in the first row\n");
        out.append("    notes:");
        raw.notes.entries().forEach(entry -> out.append(' ').append(entry.note().code()).append('=')
                .append(entry.count()));
        out.append('\n');
    }

    private static void collect(StyleBook.Format format, Set<String> fonts, Set<String> fills, Set<String> colours) {
        if (format == null || format.look() == null) {
            return;
        }
        if (format.look().font() != null) {
            fonts.add(format.look().font());
        }
        if (format.look().fill() != null) {
            fills.add(format.look().fill());
        }
        if (format.look().colour() != null) {
            colours.add(format.look().colour());
        }
    }

    private static void imported(StringBuilder out, SpreadsheetImporter importer, byte[] file, int index) {
        try {
            SpreadsheetImporter.Imported imported = importer.read(file, Format.XLSX,
                    new SheetImportBuilder.Options(null, 2, RowsMode.FIXED, index, true));
            SheetDocument.Tab tab = imported.checked().document().sheets().get(0);
            out.append("    as a sheet: ").append(imported.report().columns()).append(" columns, ")
                    .append(imported.report().rows()).append(" rows, ").append(imported.report().mergedAreas())
                    .append(" merged areas, ").append(tab.looks().size()).append(" looks, ").append(tab.links().size())
                    .append(" links, ").append(tab.inputs().size()).append(" yes/no or typed cells, ")
                    .append(tab.conditions().size()).append(" conditional colours, ")
                    .append(imported.checked().bytes()).append(" bytes; first row is a title: ")
                    .append(imported.report().firstRowIsTitle()).append('\n');
        } catch (ImportRefusal e) {
            out.append("    as a sheet: refused with code ").append(e.code()).append('\n');
        }
    }
}
