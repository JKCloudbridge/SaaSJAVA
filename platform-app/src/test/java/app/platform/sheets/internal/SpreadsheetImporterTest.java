package app.platform.sheets.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.platform.sheets.internal.SheetGrid.Cell;
import app.platform.sheets.internal.SheetGrid.FontSize;
import app.platform.sheets.internal.SheetGrid.RowsMode;
import app.platform.sheets.internal.SpreadsheetImporter.Format;
import app.platform.sheets.internal.SpreadsheetImporter.Imported;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Random;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.LongRange;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The reader of spreadsheet files, with no database (UI3B, ADR-0195): a good workbook and a good text file become the
 * grid the author was told about; what a sheet cannot hold is counted; each limit holds at the limit and one above;
 * and whatever the file is, the answer is a grid the validator accepts or a refusal with a code and words that repeat
 * nothing of the file. The hostile files are in {@link SpreadsheetCorpusTest}.
 */
class SpreadsheetImporterTest {

    static final SheetProperties.Limits LIMITS = SheetProperties.Limits.standard();
    static final SheetImportBuilder.Options NAMED_ROWS = new SheetImportBuilder.Options(true, 2, RowsMode.FIXED);

    static SpreadsheetImporter importer(ImportProperties settings) {
        return new SpreadsheetImporter(settings, new SheetProperties(LIMITS, 4096),
                new SheetGridValidator(LIMITS, new SheetSchemas()));
    }

    static Imported read(byte[] file, Format format) {
        return importer(ImportProperties.standard()).read(file, format, NAMED_ROWS);
    }

    static ImportRefusal refused(byte[] file, Format format) {
        return refused(ImportProperties.standard(), file, format, NAMED_ROWS);
    }

    static ImportRefusal refused(ImportProperties settings, byte[] file, Format format,
            SheetImportBuilder.Options options) {
        Throwable thrown = null;
        try {
            importer(settings).read(file, format, options);
        } catch (RuntimeException e) {
            thrown = e;
        }
        assertThat(thrown).as("a refusal").isInstanceOf(ImportRefusal.class);
        return (ImportRefusal) thrown;
    }

    private static Cell cellAt(SheetGrid grid, int row, int column) {
        return grid.cells().get(grid.rows().get(row).id() + ":" + grid.columns().get(column).id());
    }

    private static byte[] csv(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    // ---- a good workbook ----

    private static byte[] goodWorkbook() {
        return new XlsxFile()
                .row(1, XlsxFile.text("A1", "Name", XlsxFile.BOLD), XlsxFile.text("B1", "Qty", XlsxFile.BOLD),
                        XlsxFile.text("C1", "Date", XlsxFile.BOLD), XlsxFile.text("D1", "Note", XlsxFile.BOLD))
                .row(2, 24, XlsxFile.text("A2", "Apple"), XlsxFile.number("B2", "12", XlsxFile.PLAIN),
                        XlsxFile.number("C2", "45000", XlsxFile.DATE), XlsxFile.formula("D2", "B2*2", "24"))
                .row(3, XlsxFile.text("A3", "Heading", XlsxFile.TITLE), XlsxFile.text("B3", "hidden"),
                        XlsxFile.error("C3"), XlsxFile.blank("D3"))
                .widths(1, 1, 20).merge("A3:B3").secondSheet()
                .part("xl/media/image1.png", new byte[] {1, 2, 3}).part("xl/charts/chart1.xml", new byte[] {1})
                .bytes();
    }

    @Test
    void aWorkbookBecomesTheGridTheAuthorWasTold() {
        Imported imported = read(goodWorkbook(), Format.XLSX);
        SheetGrid grid = imported.checked().grid();

        assertThat(grid.dimension()).isEqualTo(2);
        assertThat(grid.columns()).extracting(SheetGrid.Column::label).containsExactly("Name", "Qty", "Date", "Note");
        assertThat(grid.columns().get(0).width()).isEqualTo(145);
        assertThat(grid.columns().get(1).width()).isNull();
        assertThat(grid.rows()).hasSize(2);
        assertThat(grid.rows().get(0).height()).isEqualTo(32);
        assertThat(cellAt(grid, 0, 0).value()).isEqualTo("Apple");
        assertThat(cellAt(grid, 0, 1).value()).isEqualTo("12");
        assertThat(cellAt(grid, 0, 2).value()).isEqualTo("2023-03-15");
        assertThat(cellAt(grid, 0, 3).value()).isEqualTo("24");
        assertThat(cellAt(grid, 1, 0).style().bold()).isTrue();
        assertThat(cellAt(grid, 1, 0).style().italic()).isTrue();
        assertThat(cellAt(grid, 1, 0).style().fontSize()).isEqualTo(FontSize.TITLE);
        assertThat(grid.merges()).hasSize(1);
    }

    @Test
    void emptyCellsAreLeftToBeTypedInto() {
        SheetGrid grid = read(goodWorkbook(), Format.XLSX).checked().grid();

        assertThat(cellAt(grid, 1, 2)).as("a cell that held an error").isNull();
        assertThat(cellAt(grid, 1, 3)).as("a cell with a style and no text").isNull();
        assertThat(cellAt(grid, 1, 1)).as("a cell under a merged area").isNull();
    }

    @Test
    void whatTheSheetCannotHoldIsCountedInPlainWords() {
        SheetImportBuilder.Report report = read(goodWorkbook(), Format.XLSX).report();

        assertThat(report.columns()).isEqualTo(4);
        assertThat(report.rows()).isEqualTo(2);
        assertThat(report.mergedAreas()).isEqualTo(1);
        Map<String, Integer> counts = new java.util.HashMap<>();
        report.notes().forEach(entry -> counts.put(entry.note().code(), entry.count()));
        assertThat(counts).containsEntry("images", 1).containsEntry("charts", 1).containsEntry("other_sheets", 1)
                .containsEntry("error_cells", 1).containsEntry("covered_cells", 1).containsEntry("formulas", 1)
                .containsEntry("dates", 1);
        assertThat(report.notes()).extracting(entry -> entry.note().words(entry.count()))
                .contains("1 image was left out.", "1 formula was read as its last value.");
    }

    @Test
    void theFirstRowIsABodyRowWhenTheAuthorSaysSo() {
        Imported imported = importer(ImportProperties.standard()).read(goodWorkbook(), Format.XLSX,
                new SheetImportBuilder.Options(false, 2, RowsMode.FIXED));
        SheetGrid grid = imported.checked().grid();

        assertThat(grid.columns()).extracting(SheetGrid.Column::label).containsExactly("Column 1", "Column 2",
                "Column 3", "Column 4");
        assertThat(grid.rows()).hasSize(3);
        assertThat(cellAt(grid, 0, 0).value()).isEqualTo("Name");
    }

    @Test
    void aSheetWhoseFillerAddsTheRowsKeepsTheColumnNamesOnly() {
        Imported imported = importer(ImportProperties.standard()).read(goodWorkbook(), Format.XLSX,
                new SheetImportBuilder.Options(true, 1, RowsMode.REPEATABLE));
        SheetGrid grid = imported.checked().grid();

        assertThat(grid.dimension()).isEqualTo(1);
        assertThat(grid.rows()).isEmpty();
        assertThat(grid.cells()).isEmpty();
        assertThat(grid.columns()).hasSize(4);
        Map<String, Integer> counts = new java.util.HashMap<>();
        imported.report().notes().forEach(entry -> counts.put(entry.note().code(), entry.count()));
        assertThat(counts).containsEntry("rows_not_kept", 2).containsEntry("merges_left_out", 1);
    }

    @Test
    void sharedTextsAndBooleansAreRead() {
        byte[] file = new XlsxFile().sharedStrings("Alpha", "Beta")
                .row(1, XlsxFile.shared("A1", 0), XlsxFile.shared("B1", 1))
                .row(2, XlsxFile.bool("A2", true), XlsxFile.bool("B2", false)).bytes();

        SheetGrid grid = read(file, Format.XLSX).checked().grid();

        assertThat(grid.columns()).extracting(SheetGrid.Column::label).containsExactly("Alpha", "Beta");
        assertThat(cellAt(grid, 0, 0).value()).isEqualTo("TRUE");
        assertThat(cellAt(grid, 0, 1).value()).isEqualTo("FALSE");
    }

    @Test
    void mergesThatTouchTheFirstRowOrOverlapAreLeftOutAndCounted() {
        byte[] file = new XlsxFile().row(1, XlsxFile.text("A1", "Title"), XlsxFile.text("B1", "Other"))
                .row(2, XlsxFile.text("A2", "x"), XlsxFile.text("B2", "y"), XlsxFile.text("C2", "z"))
                .row(3, XlsxFile.text("A3", "p"))
                .merge("A1:B1").merge("A2:B3").merge("B3:C3").bytes();

        Imported imported = read(file, Format.XLSX);

        assertThat(imported.checked().grid().merges()).hasSize(1);
        Map<String, Integer> counts = new java.util.HashMap<>();
        imported.report().notes().forEach(entry -> counts.put(entry.note().code(), entry.count()));
        assertThat(counts).containsEntry("merges_left_out", 2);
    }

    @Test
    void dateSerialsAreWrittenYearMonthDay() {
        assertThat(XlsxReader.date(45000, false)).isEqualTo("2023-03-15");
        assertThat(XlsxReader.date(45000.5, false)).isEqualTo("2023-03-15 12:00");
        assertThat(XlsxReader.date(0.25, false)).isEqualTo("06:00");
        assertThat(XlsxReader.date(1, false)).isEqualTo("1900-01-01");
        assertThat(XlsxReader.date(61, false)).isEqualTo("1900-03-01");
        assertThat(XlsxReader.date(0, true)).isEqualTo("1904-01-01");
        assertThat(XlsxReader.date(-1, false)).isNull();
        assertThat(XlsxReader.isDateFormat("yyyy-mm-dd")).isTrue();
        assertThat(XlsxReader.isDateFormat("0.00 \"days\"")).isFalse();
        assertThat(XlsxReader.isDateFormat("#,##0")).isFalse();
    }

    @Test
    void aCellReferenceIsParsedAndAnythingElseIsNot() {
        assertThat(XlsxReader.reference("A1")).containsExactly(0, 0);
        assertThat(XlsxReader.reference("AA10")).containsExactly(9, 26);
        assertThat(XlsxReader.reference("XFD1048576")).containsExactly(1_048_575, 16_383);
        assertThat(XlsxReader.reference("XFE1")).isNull();
        assertThat(XlsxReader.reference("A0")).isNull();
        assertThat(XlsxReader.reference("A1048577")).isNull();
        assertThat(XlsxReader.reference("1A")).isNull();
        assertThat(XlsxReader.reference("")).isNull();
        assertThat(SheetImportBuilder.letters(0)).isEqualTo("A");
        assertThat(SheetImportBuilder.letters(25)).isEqualTo("Z");
        assertThat(SheetImportBuilder.letters(26)).isEqualTo("AA");
    }

    // ---- a good text file ----

    @Test
    void aTextFileWithQuotesAndLineBreaksInACellIsRead() {
        SheetGrid grid = read(csv("﻿Name,Note\r\n\"Smith, A\",\"two\nlines\"\"quoted\"\"\"\r\nplain,\r\n"),
                Format.CSV).checked().grid();

        assertThat(grid.columns()).extracting(SheetGrid.Column::label).containsExactly("Name", "Note");
        assertThat(grid.rows()).hasSize(2);
        assertThat(cellAt(grid, 0, 0).value()).isEqualTo("Smith, A");
        assertThat(cellAt(grid, 0, 1).value()).isEqualTo("two\nlines\"quoted\"");
        assertThat(cellAt(grid, 1, 1)).isNull();
    }

    @Test
    void theSeparatorIsTheOneTheFirstLineHoldsMost() {
        assertThat(SheetCsvReader.delimiterOf("a;b;c\n1,2,3")).isEqualTo(';');
        assertThat(SheetCsvReader.delimiterOf("a\tb\tc")).isEqualTo('\t');
        assertThat(SheetCsvReader.delimiterOf("a,b;c")).isEqualTo(',');
        assertThat(SheetCsvReader.delimiterOf("\"a;b;c\",d")).isEqualTo(',');
        assertThat(SheetCsvReader.delimiterOf("single")).isEqualTo(',');
    }

    // ---- limits: at the limit, and one above ----

    private static String header(int columns) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < columns; i++) {
            out.append(i == 0 ? "" : ",").append("h").append(i);
        }
        return out.toString();
    }

    @Test
    void columnsAreAcceptedAtTheLimitAndRefusedAboveIt() {
        assertThat(read(csv(header(LIMITS.maxColumns()) + "\n"), Format.CSV).checked().grid().columns())
                .hasSize(LIMITS.maxColumns());

        ImportRefusal refusal = refused(csv(header(LIMITS.maxColumns() + 1) + "\n"), Format.CSV);
        assertThat(refusal.code()).isEqualTo("too_many_columns");
        assertThat(refusal.words()).contains("101").contains("100");
    }

    @Test
    void rowsAreAcceptedAtTheLimitAndRefusedAboveIt() {
        String ok = "h\n" + "x\n".repeat(LIMITS.maxRows());
        assertThat(read(csv(ok), Format.CSV).checked().grid().rows()).hasSize(LIMITS.maxRows());

        ImportRefusal refusal = refused(csv(ok + "x\n"), Format.CSV);
        assertThat(refusal.code()).isEqualTo("too_many_rows");
        assertThat(refusal.words()).contains("501").contains("500");
    }

    @Test
    void aTextIsAcceptedAtTheLimitAndRefusedAboveItWithItsPlace() {
        String at = "x".repeat(LIMITS.maxCellChars());
        assertThat(read(csv("h\n" + at + "\n"), Format.CSV).checked().grid().cells()).hasSize(1);

        ImportRefusal refusal = refused(csv("h\n" + at + "x\n"), Format.CSV);
        assertThat(refusal.code()).isEqualTo("text_too_long");
        assertThat(refusal.words()).startsWith("Row 2, column A").contains("1000").doesNotContain("xxx");
    }

    @Test
    void aLabelHasItsOwnLimit() {
        ImportRefusal refusal = refused(csv("x".repeat(LIMITS.maxLabelChars() + 1) + "\nb\n"), Format.CSV);

        assertThat(refusal.code()).isEqualTo("text_too_long");
        assertThat(refusal.words()).startsWith("Row 1, column A").contains("200");
    }

    @Test
    void mergedAreasAreAcceptedAtTheLimitAndRefusedAboveIt() {
        assertThat(read(workbookWithMerges(LIMITS.maxMerges()), Format.XLSX).checked().grid().merges())
                .hasSize(LIMITS.maxMerges());

        assertThat(refused(workbookWithMerges(LIMITS.maxMerges() + 1), Format.XLSX).code())
                .isEqualTo("too_many_merges");
    }

    /** A header of 100 columns, then 50 merged areas (two cells each) in every row below it, as many as asked. */
    private static byte[] workbookWithMerges(int count) {
        XlsxFile file = new XlsxFile();
        String[] header = new String[100];
        for (int c = 0; c < header.length; c++) {
            header[c] = XlsxFile.text(SheetImportBuilder.letters(c) + "1", "h");
        }
        file.row(1, header);
        for (int i = 0; i < count; i++) {
            int row = 2 + i / 50;
            int first = 2 * (i % 50);
            String left = SheetImportBuilder.letters(first) + row;
            file.merge(left + ":" + SheetImportBuilder.letters(first + 1) + row);
        }
        // The last row must hold something, or the areas in it would lie outside the part that is read.
        int last = 2 + (count - 1) / 50;
        file.row(last, XlsxFile.text("A" + last, "x"));
        return file.bytes();
    }

    @Test
    void theFileSizeIsCheckedBeforeAnythingIsRead() {
        ImportProperties small = new ImportProperties(1_000, 20_000_000, 100_000_000, 100, 200_000, 4);

        ImportRefusal refusal = refused(small, new byte[1_001], Format.CSV, NAMED_ROWS);

        assertThat(refusal.code()).isEqualTo("file_too_large");
        assertThat(refusal.kind()).isEqualTo(ImportRefusal.Kind.TOO_LARGE);
    }

    @Test
    void theNumberOfCellsReadIsBounded() {
        ImportProperties few = new ImportProperties(5_000_000, 20_000_000, 100_000_000, 100, 100, 4);
        XlsxFile file = new XlsxFile();
        for (int row = 1; row <= 60; row++) {
            file.row(row, XlsxFile.text("A" + row, "x"), XlsxFile.text("B" + row, "y"));
        }

        ImportRefusal refusal = refused(few, file.bytes(), Format.XLSX, NAMED_ROWS);

        assertThat(refusal.code()).isEqualTo("cells");
        assertThat(refusal.kind()).isEqualTo(ImportRefusal.Kind.TOO_LARGE);
    }

    @Test
    void oneFileIsRefusedWhenAllThePlacesAreTaken() throws InterruptedException {
        SpreadsheetImporter importer = importer(new ImportProperties(5_000_000, 20_000_000, 100_000_000, 100,
                200_000, 1));
        importer.places().acquire();
        try {
            assertThatThrownBy(() -> importer.read(csv("h\nx\n"), Format.CSV, NAMED_ROWS))
                    .isInstanceOfSatisfying(ImportRefusal.class, refusal -> {
                        assertThat(refusal.code()).isEqualTo("busy");
                        assertThat(refusal.kind()).isEqualTo(ImportRefusal.Kind.BUSY);
                    });
        } finally {
            importer.places().release();
        }
        assertThat(importer.read(csv("h\nx\n"), Format.CSV, NAMED_ROWS)).isNotNull();
    }

    // ---- hostile files that need more than a line of the corpus ----

    @Test
    @Timeout(20)
    void anExternalEntityIsNeverResolvedAndAnEntityBombIsNeverExpanded() {
        String doctype = "<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY xxe SYSTEM \"file:///etc/hosts\">"
                + "<!ENTITY a \"" + "a".repeat(80) + "\">"
                + "<!ENTITY b \"&a;&a;&a;&a;&a;&a;&a;&a;&a;&a;\"><!ENTITY c \"&b;&b;&b;&b;&b;&b;&b;&b;&b;&b;\">]>";
        byte[] file = new XlsxFile().sheetPrefix(doctype)
                .row(1, "<c r=\"A1\" t=\"inlineStr\"><is><t>&xxe;</t></is></c>",
                        "<c r=\"B1\" t=\"inlineStr\"><is><t>&c;</t></is></c>", XlsxFile.text("C1", "ok"))
                .bytes();

        Throwable thrown = null;
        Imported imported = null;
        try {
            imported = read(file, Format.XLSX);
        } catch (RuntimeException e) {
            thrown = e;
        }

        if (thrown != null) {
            assertThat(thrown).isInstanceOf(ImportRefusal.class);
        } else {
            assertThat(imported.checked().canonical()).doesNotContain("localhost").doesNotContain("127.0.0.1")
                    .doesNotContain("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        }
    }

    // ---- the property: a grid the validator accepts, or a refusal ----

    @Property(tries = 300)
    void anyTextFileGivesAcceptedGridOrARefusalThatRepeatsNothing(@ForAll String text) {
        assertAcceptedOrRefused(csv(text), Format.CSV);
    }

    @Property(tries = 150)
    void anyWorkbookGivesAcceptedGridOrARefusalThatRepeatsNothing(
            @ForAll @LongRange(min = 1, max = 1_000_000) long seed) {
        Random random = new Random(seed);
        String[] pieces = {"plain", "two words", "<b>x", "a&b", "x".repeat(1_100), "9", "", " ", "\u0001ctl",
                "line\nbreak"};
        XlsxFile file = new XlsxFile();
        int rows = 1 + random.nextInt(12);
        for (int row = 1; row <= rows; row++) {
            int cells = random.nextInt(6);
            String[] xml = new String[cells];
            for (int c = 0; c < cells; c++) {
                String ref = (char) ('A' + random.nextInt(6)) + Integer.toString(row);
                xml[c] = XlsxFile.text(ref, pieces[random.nextInt(pieces.length)],
                        random.nextInt(4));
            }
            file.row(row, xml);
        }
        for (int m = random.nextInt(4); m > 0; m--) {
            int top = 1 + random.nextInt(rows);
            file.merge("A" + top + ":" + (char) ('B' + random.nextInt(4)) + (top + random.nextInt(3)));
        }
        // The control character among the pieces is not legal XML: that file is refused, which is also an answer.
        assertAcceptedOrRefused(file.bytes(), Format.XLSX);
    }

    private static void assertAcceptedOrRefused(byte[] file, Format format) {
        try {
            Imported imported = read(file, format);
            assertThat(imported.checked().canonical()).isNotBlank();
            assertThat(new SheetGridValidator(LIMITS, new SheetSchemas()).check(
                    imported.checked().canonical().getBytes(StandardCharsets.UTF_8)).accepted()).isTrue();
        } catch (ImportRefusal e) {
            assertThat(e.code()).isNotBlank();
            assertThat(e.words()).isNotBlank().doesNotContain("<b>").doesNotContain("\u0001")
                    .doesNotContain("x".repeat(50));
        }
    }

    @Test
    void theCornersOfTheCasesAreWhatTheyAreMeantToBe() {
        assertThat(List.of(Format.values())).containsExactly(Format.XLSX, Format.CSV);
        assertThat(Format.of("XLSX")).contains(Format.XLSX);
        assertThat(Format.of("xls")).isEmpty();
        assertThat(Format.of(null)).isEmpty();
    }
}
