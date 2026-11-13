package app.platform.sheets.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.sheets.internal.SheetDocument.Tab;
import app.platform.sheets.internal.SheetGrid.RowsMode;
import app.platform.sheets.internal.SpreadsheetImporter.Format;
import app.platform.sheets.internal.SpreadsheetImporter.Imported;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A workbook becomes a document of format version 2 that looks like the file (SH1B, ADR-0253), with no database: the
 * look of the cells, a link, the frozen panes, the page set-up and a conditional colour all reach the document the
 * validator accepts; the limits of version 2 hold (a longer fixed text, a narrow column, a short row); and the sheet
 * that is made without the switch is the sheet it always was.
 */
class ImportFormatTwoTest {

    private static final SheetProperties.Limits LIMITS = SheetProperties.Limits.standard();
    private static final SheetImportBuilder.Options TWO = new SheetImportBuilder.Options(null, 2, RowsMode.FIXED, null,
            true);

    private static SpreadsheetImporter importer(boolean formatTwo) {
        return new SpreadsheetImporter(ImportProperties.standard(),
                new SheetProperties(LIMITS, 4096, new SheetProperties.FormatTwo(formatTwo)),
                new SheetGridValidator(LIMITS, new SheetSchemas(), formatTwo));
    }

    private static Imported read(byte[] file) {
        return importer(true).read(file, Format.XLSX, TWO);
    }

    private static Tab tab(Imported imported) {
        assertThat(imported.checked().document()).as("a document of format version 2").isNotNull();
        return imported.checked().document().sheets().get(0);
    }

    private static String key(Tab tab, int row, int column) {
        return tab.body().rows().get(row).id() + ":" + tab.body().columns().get(column).id();
    }

    private static byte[] richFile() {
        return RichWorkbook.file()
                .beforeData("<sheetPr><pageSetUpPr fitToPage=\"1\"/></sheetPr><sheetViews><sheetView "
                        + "workbookViewId=\"0\">"
                        + "<pane xSplit=\"1\" ySplit=\"2\" topLeftCell=\"B3\" activePane=\"bottomRight\" "
                        + "state=\"frozen\"/></sheetView></sheetViews>")
                .row(1, XlsxFile.text("A1", "Title", RichWorkbook.HEADING), XlsxFile.text("B1", "Note",
                        RichWorkbook.UNKNOWN_FONT), XlsxFile.text("C1", "Score"))
                .row(2, XlsxFile.text("A2", "Check"), "<c r=\"B2\" s=\"" + RichWorkbook.YELLOW_BOX + "\"/>",
                        XlsxFile.number("C2", "0.5", RichWorkbook.PERCENT))
                .row(3, XlsxFile.text("A3", "Other"), XlsxFile.text("B3", "link"), XlsxFile.number("C3", "0.9", 0))
                .afterMerges("<conditionalFormatting sqref=\"C2:C10\"><cfRule type=\"cellIs\" "
                        + "dxfId=\"0\" priority=\"1\" "
                        + "operator=\"lessThan\"><formula>0.85</formula></cfRule></conditionalFormatting>"
                        + "<hyperlinks><hyperlink ref=\"B3\" r:id=\"rId1\"/></hyperlinks>"
                        + "<pageSetup paperSize=\"9\" orientation=\"landscape\" fitToWidth=\"1\" fitToHeight=\"0\"/>")
                .part("xl/worksheets/_rels/sheet1.xml.rels",
                        ("<Relationships xmlns=\"http://schemas.openxmlformats.org/"
                        + "package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas."
                        + "openxmlformats.org/officeDocument/2006/relationships/hyperlink\" "
                        + "Target=\"https://example.test/page\" TargetMode=\"External\"/></Relationships>")
                        .getBytes(StandardCharsets.UTF_8))
                .bytes();
    }

    @Test
    void theLookTheLinkThePanesThePageAndTheConditionalColourReachTheDocument() {
        Tab tab = tab(read(richFile()));

        assertThat(tab.title()).isEqualTo("One");
        assertThat(tab.body().columns()).extracting(SheetGrid.Column::label).containsExactly("Title", "Note", "Score");
        assertThat(tab.looks().get(key(tab, 0, 1)).fill()).as("the yellow box").isEqualTo("#ffff00");
        assertThat(tab.looks().get(key(tab, 0, 2)).numberFormat()).isEqualTo("PERCENT");
        assertThat(tab.links()).containsEntry(key(tab, 1, 1), "https://example.test/page");
        assertThat(tab.freeze()).isEqualTo(new SheetDocument.Freeze(1, 1));
        assertThat(tab.print()).isEqualTo(new SheetDocument.Print("A4", "LANDSCAPE", true, false));
        assertThat(tab.conditions()).hasSize(1);
        SheetDocument.Condition condition = tab.conditions().get(0);
        assertThat(condition.from()).isEqualTo(key(tab, 0, 2));
        assertThat(condition.to()).as("cut to the part of the table that was kept").isEqualTo(key(tab, 1, 2));
        assertThat(condition.operator()).isEqualTo("LT");
        assertThat(condition.value()).isEqualTo("0.85");
        assertThat(condition.fill()).isEqualTo("#ffc7ce");
        assertThat(condition.colour()).isEqualTo("#9c0006");
    }

    @Test
    void aFixedTextOfMoreThanAThousandCharactersIsAcceptedInFormatTwoAndRefusedWithoutIt() {
        byte[] file = new XlsxFile().row(1, XlsxFile.text("A1", "h")).row(2, XlsxFile.text("A2", "n".repeat(1_172)))
                .bytes();

        Imported two = read(file);
        ImportRefusal one = refusal(file, new SheetImportBuilder.Options(null, 2, RowsMode.FIXED));

        assertThat(two.checked().document()).isNotNull();
        assertThat(one.code()).isEqualTo("text_too_long");
    }

    @Test
    void aNarrowColumnAndAShortRowAreKeptAsTheFileStatesThem() {
        byte[] file = new XlsxFile().widths(2, 2, 5.14).row(1, XlsxFile.text("A1", "h"), XlsxFile.text("B1", "i"))
                .row(2, 7, XlsxFile.text("A2", "a"), XlsxFile.text("B2", "b")).bytes();

        Tab tab = tab(read(file));

        assertThat(tab.body().columns().get(1).width()).as("5.14 characters wide").isBetween(35, 37);
        assertThat(tab.body().rows().get(0).height()).as("7 points high").isBetween(8, 10);
    }

    @Test
    void withoutTheFlagTheSheetIsTheSheetItAlwaysWasAndHoldsNoLook() {
        Imported imported = importer(false).read(richFile(), Format.XLSX, SpreadsheetImporterTest.NAMED_ROWS);

        assertThat(imported.checked().document()).isNull();
        assertThat(imported.checked().canonical()).doesNotContain("\"formatVersion\":2").doesNotContain("\"look\"");
    }

    @Test
    void whenTheSwitchIsOffAFormatTwoSheetIsRefusedInFixedWords() {
        ImportRefusal refusal = refusalWith(importer(false), richFile(), TWO);

        assertThat(refusal.code()).isEqualTo("grid_invalid");
        assertThat(refusal.words()).contains(SheetFormats.EARLIER_FORMAT);
    }

    @Test
    void aSheetWhoseFillerAddsTheRowsGetsTheColumnNamesOnlyAndIsToldWhatWasLeftOut() {
        SheetImportBuilder.Options rowByRow = new SheetImportBuilder.Options(null, 1, RowsMode.REPEATABLE, null, true);

        Imported imported = importer(true).read(richFile(), Format.XLSX, rowByRow);

        Tab tab = tab(imported);
        assertThat(tab.looks()).isEmpty();
        assertThat(tab.conditions()).isEmpty();
        assertThat(imported.report().notes()).extracting(entry -> entry.note().code())
                .contains("conditions_left_out", "rows_not_kept");
    }

    @Test
    void theReportListsTheVisibleWorksheetsForTheChoice() {
        byte[] file = new XlsxFile().row(1, XlsxFile.text("A1", "first"))
                .extraSheet("Data", null, XlsxFile.sheetXml("<row r=\"1\">" + XlsxFile.text("A1", "second") + "</row>"))
                .extraSheet("Hidden", "hidden", XlsxFile.sheetXml("<row r=\"1\">" + XlsxFile.text("A1", "third")
                        + "</row>")).bytes();

        Imported imported = read(file);

        assertThat(imported.report().worksheets()).extracting(RawSheet.Worksheet::title).containsExactly("One", "Data");
        assertThat(imported.report().worksheet()).isEqualTo(0);
        assertThat(imported.report().notes()).extracting(entry -> entry.note().code())
                .contains("other_sheets", "hidden_sheets");
        assertThat(List.of(tab(importer(true).read(file, Format.XLSX, new SheetImportBuilder.Options(null, 2,
                RowsMode.FIXED, 1, true))).title())).containsExactly("Data");
    }

    private static ImportRefusal refusal(byte[] file, SheetImportBuilder.Options options) {
        return refusalWith(importer(false), file, options);
    }

    private static ImportRefusal refusalWith(SpreadsheetImporter importer, byte[] file,
            SheetImportBuilder.Options options) {
        try {
            importer.read(file, Format.XLSX, options);
        } catch (ImportRefusal e) {
            return e;
        }
        throw new AssertionError("a refusal was expected");
    }
}
