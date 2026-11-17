package app.platform.sheets.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.sheets.internal.SheetGrid.RowsMode;
import app.platform.sheets.internal.SpreadsheetImporter.Format;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The places where a workbook held an embedded file become attachment cells (SH5.1, ADR-0263): the form asked for a
 * file there. A cell inside the table takes one: an empty cell as it is, a cell with text with the text as its prompt;
 * a place outside the table and a sheet whose filler adds the rows keep the place in the list for the author. The file
 * itself is never kept. No database.
 *
 * <p>The sheet has four columns (A to D) and two rows in the file: row 1 names the columns, row 2 holds a text in A2
 * and nothing else, so B2, C2 and D2 are empty cells of the sheet's first row.
 */
class AttachmentCellsFromUploadTest {

    private static final String R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";

    /** An embedded file drawn over a cell, as the old-style drawing writes it (column, then row, from zero). */
    private static String file(int id, int column, int row) {
        return "<v:shape id=\"_x0000_s" + id + "\" type=\"#_x0000_t75\"><x:ClientData ObjectType=\"Pict\"><x:Anchor>"
                + column + ", 3, " + row + ", 1, " + column + ", 40, " + row + ", 8</x:Anchor></x:ClientData>"
                + "</v:shape>";
    }

    private static byte[] workbook(String... shapes) {
        String vml = "<xml>" + String.join("", shapes) + "</xml>";
        return new XlsxFile()
                .row(1, XlsxFile.text("A1", "a"), XlsxFile.text("B1", "b"), XlsxFile.text("C1", "c"),
                        XlsxFile.text("D1", "d"))
                .row(2, XlsxFile.text("A2", "z"))
                .afterMerges("<legacyDrawing r:id=\"rId1\"/>")
                .part("xl/worksheets/_rels/sheet1.xml.rels", ("<Relationships xmlns=\""
                        + "http://schemas.openxmlformats.org/package/2006/relationships\">"
                        + "<Relationship Id=\"rId1\" Type=\"" + R
                        + "/vmlDrawing\" Target=\"../drawings/vmlDrawing1.vml\"/></Relationships>")
                        .getBytes(StandardCharsets.UTF_8))
                .part("xl/drawings/vmlDrawing1.vml", vml.getBytes(StandardCharsets.UTF_8)).bytes();
    }

    private static SpreadsheetImporter.Imported read(byte[] bytes, int dimension, RowsMode mode) {
        SpreadsheetImporter importer = new SpreadsheetImporter(ImportProperties.standard(),
                new SheetProperties(SheetProperties.Limits.standard(), 4096, new SheetProperties.FormatTwo(true)),
                new SheetGridValidator(SheetProperties.Limits.standard(), new SheetSchemas(), true));
        return importer.read(bytes, Format.XLSX, new SheetImportBuilder.Options(null, dimension, mode, null, true));
    }

    private static int count(SpreadsheetImporter.Imported imported, ImportNote note) {
        return imported.report().notes().stream().filter(entry -> entry.note() == note)
                .mapToInt(ImportNote.Entry::count).sum();
    }

    @Test
    void anEmptyCellWhereAFileStoodBecomesAnAttachmentCellWithTheDefaults() {
        SpreadsheetImporter.Imported imported = read(workbook(file(1, 1, 1), file(2, 3, 1)), 2, RowsMode.FIXED);

        Map<String, SheetDocument.Input> inputs = imported.checked().document().sheets().get(0).inputs();
        assertThat(inputs).hasSize(2).allSatisfy((key, input) -> {
            assertThat(input.type()).isEqualTo("attachment");
            assertThat(input.required()).isFalse();
            assertThat(input.cls()).isEqualTo(SheetDocument.InputClass.FILLER);
            // five files is the default of an attachment cell, so the canonical form says nothing about it
            assertThat(input.maxFiles()).isNull();
        });
        assertThat(count(imported, ImportNote.ATTACHMENT_CELLS)).isEqualTo(2);
        assertThat(count(imported, ImportNote.FILES_NOT_PLACED)).isZero();
        assertThat(imported.report().embeddedFilePlaces()).isEmpty();
    }

    @Test
    void twoFilesOnOneCellMakeOneAttachmentCell() {
        SpreadsheetImporter.Imported imported = read(workbook(file(1, 2, 1), file(2, 2, 1)), 2, RowsMode.FIXED);

        assertThat(imported.checked().document().sheets().get(0).inputs()).hasSize(1);
        assertThat(count(imported, ImportNote.ATTACHMENT_CELLS)).isEqualTo(1);
        assertThat(count(imported, ImportNote.FILES_NOT_PLACED)).isZero();
    }

    @Test
    void aPlaceOutsideTheTableIsListedByThePlaceInTheFileAndOnTheSheet() {
        // B1 is the row that names the columns, which is not a row of the sheet
        SpreadsheetImporter.Imported imported = read(workbook(file(1, 1, 0)), 2, RowsMode.FIXED);

        assertThat(imported.checked().document().sheets().get(0).inputs()).isEmpty();
        assertThat(count(imported, ImportNote.FILES_NOT_PLACED)).isEqualTo(1);
        SheetImportBuilder.Report report = imported.report();
        assertThat(report.embeddedFilePlaces()).containsExactly(new RawSheet.Place(0, 1));
        assertThat(report.sheetRow(new RawSheet.Place(0, 1), report.rows())).isNull();
        // the cell A2 is in the table: file row 2 is the sheet's row 1 (the row above named the columns)
        assertThat(report.sheetRow(new RawSheet.Place(1, 0), report.rows())).isEqualTo(1);
    }

    @Test
    void aFileOverACellWithTextMakesAnAttachmentCellWhoseTextIsItsPrompt() {
        // A2 holds the text "z"; the file stands over it (SH5.1: the text and the file share one box)
        SpreadsheetImporter.Imported imported = read(workbook(file(1, 0, 1), file(2, 2, 1)), 2, RowsMode.FIXED);

        SheetDocument.Tab tab = imported.checked().document().sheets().get(0);
        assertThat(tab.inputs()).hasSize(2);
        SheetDocument.Input withText = tab.inputs().values().stream().filter(input -> input.prompt() != null)
                .findFirst().orElseThrow();
        assertThat(withText.type()).isEqualTo("attachment");
        assertThat(withText.prompt()).isEqualTo("z");
        assertThat(withText.required()).isFalse();
        assertThat(withText.maxFiles()).isNull();
        // the text left the body cell: a stated cell holds no text of its own
        assertThat(tab.body().cells().values()).extracting(SheetGrid.Cell::value).doesNotContain("z");
        assertThat(count(imported, ImportNote.ATTACHMENT_PROMPTS)).isEqualTo(1);
        assertThat(count(imported, ImportNote.ATTACHMENT_CELLS)).isEqualTo(1);
        assertThat(count(imported, ImportNote.FILES_NOT_PLACED)).isZero();
    }

    @Test
    void aFileOverTheOtherCellOfMergedTextUsesTheMergedAreaAsOneCell() {
        byte[] bytes = new XlsxFile()
                .row(1, XlsxFile.text("A1", "a"), XlsxFile.text("B1", "b"), XlsxFile.text("C1", "c"))
                .row(2, XlsxFile.text("A2", "merged question"))
                .merge("A2:B2")
                .afterMerges("<legacyDrawing r:id=\"rId1\"/>")
                .part("xl/worksheets/_rels/sheet1.xml.rels", ("<Relationships xmlns=\""
                        + "http://schemas.openxmlformats.org/package/2006/relationships\">"
                        + "<Relationship Id=\"rId1\" Type=\"" + R
                        + "/vmlDrawing\" Target=\"../drawings/vmlDrawing1.vml\"/></Relationships>")
                        .getBytes(StandardCharsets.UTF_8))
                .part("xl/drawings/vmlDrawing1.vml",
                        ("<xml>" + file(1, 1, 1) + "</xml>").getBytes(StandardCharsets.UTF_8)).bytes();

        SpreadsheetImporter.Imported imported = read(bytes, 2, RowsMode.FIXED);

        SheetDocument.Tab tab = imported.checked().document().sheets().get(0);
        assertThat(tab.inputs()).hasSize(1).allSatisfy((key, input) -> {
            assertThat(input.type()).isEqualTo("attachment");
            assertThat(input.prompt()).isEqualTo("merged question");
        });
        assertThat(count(imported, ImportNote.ATTACHMENT_PROMPTS)).isEqualTo(1);
    }

    @Test
    void aSheetWhoseFillerAddsTheRowsMakesNoAttachmentCellAndListsEveryPlace() {
        SpreadsheetImporter.Imported imported = read(workbook(file(1, 1, 1)), 1, RowsMode.REPEATABLE);

        assertThat(imported.checked().document().sheets().get(0).inputs()).isEmpty();
        assertThat(count(imported, ImportNote.FILES_NOT_PLACED)).isEqualTo(1);
        assertThat(imported.report().embeddedFilePlaces()).hasSize(1);
        assertThat(imported.report().sheetRow(new RawSheet.Place(1, 1), imported.report().rows())).isNull();
    }
}
