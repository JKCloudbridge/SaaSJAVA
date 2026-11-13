package app.platform.sheets.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.sheets.internal.SheetGrid.RowsMode;
import app.platform.sheets.internal.SpreadsheetImporter.Format;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Check boxes (SH1B, ADR-0253 amendment): a form control drawn over the cells is placed in the cell that holds it, a
 * Yes box beside a No box is one yes/no cell, a lone box is a yes/no cell, a box that cannot be placed is reported
 * and never guessed, and a box over a cell that holds text does not replace the text. No database.
 *
 * <p>The sheet has the default sizes: a column is 64 pixels (609,600 units) and a row 20 pixels (190,500 units), so a
 * box of 230,000 by 150,000 units that starts 20,000 inside a cell lies wholly in it.
 */
class CheckBoxTest {

    private static final String XDR = "http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing";
    private static final String MC = "http://schemas.openxmlformats.org/markup-compatibility/2006";
    private static final String R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";

    private static String control(int shape, String rid, int column, int offset, int row) {
        return "<mc:AlternateContent><mc:Choice Requires=\"x14\"><control shapeId=\"" + shape + "\" r:id=\"" + rid
                + "\" name=\"c\"><controlPr><anchor moveWithCells=\"1\"><from><xdr:col>" + column + "</xdr:col>"
                + "<xdr:colOff>" + offset + "</xdr:colOff><xdr:row>" + row + "</xdr:row><xdr:rowOff>20000</xdr:rowOff>"
                + "</from><to><xdr:col>" + column + "</xdr:col><xdr:colOff>" + (offset + 230_000) + "</xdr:colOff>"
                + "<xdr:row>" + row + "</xdr:row><xdr:rowOff>170000</xdr:rowOff></to></anchor></controlPr></control>"
                + "</mc:Choice></mc:AlternateContent>";
    }

    private static String shape(int id, String caption) {
        return "<v:shape id=\"_x0000_s" + id + "\" type=\"#_x0000_t201\"><v:textbox><div><font>" + caption
                + "</font></div></v:textbox><x:ClientData ObjectType=\"Checkbox\"><x:Anchor>1, 3, 1, 1, 1, 20, 1, 8"
                + "</x:Anchor></x:ClientData></v:shape>";
    }

    /** A sheet of four columns and two rows, with the boxes given. */
    private static byte[] file(String... controls) {
        return fileWith("z", controls);
    }

    private static byte[] fileWith(String a2, String... controls) {
        StringBuilder xml = new StringBuilder("<controls xmlns:mc=\"" + MC + "\" xmlns:xdr=\"" + XDR + "\">");
        StringBuilder rels = new StringBuilder();
        int number = 1;
        for (String control : controls) {
            xml.append(control.replace("RID", "rId" + number));
            rels.append("<Relationship Id=\"rId").append(number).append("\" Type=\"").append(R)
                    .append("/ctrlProp\" Target=\"../ctrlProps/ctrlProp1.xml\"/>");
            number++;
        }
        xml.append("</controls><legacyDrawing r:id=\"rId").append(number).append("\"/>");
        rels.append("<Relationship Id=\"rId").append(number).append("\" Type=\"").append(R)
                .append("/vmlDrawing\" Target=\"../drawings/vmlDrawing1.vml\"/>");
        String vml = "<xml>" + shape(1025, "Yes") + shape(1026, "No") + "</xml>";
        return new XlsxFile()
                .row(1, XlsxFile.text("A1", "a"), XlsxFile.text("B1", "b"), XlsxFile.text("C1", "c"),
                        XlsxFile.text("D1", "d"))
                .row(2, XlsxFile.text("A2", a2))
                .afterMerges(xml.toString())
                .part("xl/worksheets/_rels/sheet1.xml.rels",
                        ("<Relationships xmlns=\"http://schemas.openxmlformats.org/"
                        + "package/2006/relationships\">" + rels + "</Relationships>").getBytes(StandardCharsets.UTF_8))
                .part("xl/ctrlProps/ctrlProp1.xml", ("<formControlPr xmlns=\"http://schemas.microsoft.com/office/"
                        + "spreadsheetml/2009/9/main\" objectType=\"CheckBox\" checked=\"Checked\"/>")
                        .getBytes(StandardCharsets.UTF_8))
                .part("xl/drawings/vmlDrawing1.vml", vml.getBytes(StandardCharsets.UTF_8)).bytes();
    }

    private static RawSheet read(byte[] file) {
        return new WorkbookReader(ImportProperties.standard(), SheetProperties.Limits.standard()).read(file, null);
    }

    @Test
    void aYesBoxBesideANoBoxIsOneYesNoCellALoneBoxIsOneAndABoxFarAwayIsNotPlaced() {
        RawSheet sheet = read(file(control(1025, "RID", 1, 20_000, 1), control(1026, "RID", 1, 300_000, 1),
                control(1027, "RID", 3, 20_000, 1), control(1028, "RID", 16_000, 20_000, 1_000_000)));

        assertThat(sheet.yesNo).containsExactly(new RawSheet.YesNo(1, 1, 2), new RawSheet.YesNo(1, 3, 1));
        assertThat(sheet.boxPairs).isEqualTo(1);
        assertThat(sheet.boxesPlaced).isEqualTo(3);
        assertThat(sheet.unplacedBoxes).hasSize(1);
    }

    @Test
    void theYesNoCellsAreStatedInTheDocumentAndTheBoxesAreCountedInPlainWords() {
        byte[] bytes = file(control(1025, "RID", 1, 20_000, 1), control(1026, "RID", 1, 300_000, 1),
                control(1027, "RID", 3, 20_000, 1));
        SpreadsheetImporter importer = new SpreadsheetImporter(ImportProperties.standard(),
                new SheetProperties(SheetProperties.Limits.standard(), 4096, new SheetProperties.FormatTwo(true)),
                new SheetGridValidator(SheetProperties.Limits.standard(), new SheetSchemas(), true));

        SpreadsheetImporter.Imported imported = importer.read(bytes, Format.XLSX,
                new SheetImportBuilder.Options(null, 2, RowsMode.FIXED, null, true));

        SheetDocument.Tab tab = imported.checked().document().sheets().get(0);
        assertThat(tab.inputs()).hasSize(2).allSatisfy((key, input) -> assertThat(input.type()).isEqualTo("boolean"));
        assertThat(imported.report().notes()).filteredOn(entry -> entry.note() == ImportNote.CHECK_BOXES)
                .extracting(ImportNote.Entry::count).containsExactly(3);
        assertThat(imported.report().notes()).filteredOn(entry -> entry.note() == ImportNote.YES_NO_PAIRS)
                .extracting(ImportNote.Entry::count).containsExactly(1);
    }

    @Test
    void aBoxOverAQuestionKeepsTheQuestionAndMakesTheFreeCellBesideItTheYesNoCell() {
        byte[] bytes = file(control(1025, "RID", 0, 20_000, 1));
        SpreadsheetImporter importer = new SpreadsheetImporter(ImportProperties.standard(),
                new SheetProperties(SheetProperties.Limits.standard(), 4096, new SheetProperties.FormatTwo(true)),
                new SheetGridValidator(SheetProperties.Limits.standard(), new SheetSchemas(), true));

        SpreadsheetImporter.Imported imported = importer.read(bytes, Format.XLSX,
                new SheetImportBuilder.Options(null, 2, RowsMode.FIXED, null, true));

        SheetDocument.Tab tab = imported.checked().document().sheets().get(0);
        assertThat(tab.inputs()).hasSize(1);
        assertThat(tab.body().cells().values()).extracting(SheetGrid.Cell::value).contains("z");
        assertThat(imported.report().notes()).filteredOn(entry -> entry.note() == ImportNote.BOXES_BESIDE)
                .extracting(ImportNote.Entry::count).containsExactly(1);
    }

    @Test
    void aBoxOverAQuestionWithNoFreeCellBesideItIsReportedAndNothingIsInvented() {
        SpreadsheetImporter importer = new SpreadsheetImporter(ImportProperties.standard(),
                new SheetProperties(SheetProperties.Limits.standard(), 4096, new SheetProperties.FormatTwo(true)),
                new SheetGridValidator(SheetProperties.Limits.standard(), new SheetSchemas(), true));

        // a box on the label row: that row is not part of the body, so there is no cell to give
        SpreadsheetImporter.Imported imported = importer.read(
                fileWith("z", control(1025, "RID", 0, 20_000, 0)), Format.XLSX,
                new SheetImportBuilder.Options(null, 2, RowsMode.FIXED, null, true));

        assertThat(imported.checked().document().sheets().get(0).inputs()).isEmpty();
        assertThat(imported.report().notes()).filteredOn(entry -> entry.note() == ImportNote.BOXES_NOT_PLACED)
                .extracting(ImportNote.Entry::count).containsExactly(1);
    }

    @Test
    void aCellThatHoldsOnlyTheWordsYesOrNoUnderABoxIsTheYesNoCell() {
        SpreadsheetImporter importer = new SpreadsheetImporter(ImportProperties.standard(),
                new SheetProperties(SheetProperties.Limits.standard(), 4096, new SheetProperties.FormatTwo(true)),
                new SheetGridValidator(SheetProperties.Limits.standard(), new SheetSchemas(), true));

        SpreadsheetImporter.Imported imported = importer.read(fileWith("Yes / No", control(1025, "RID", 0, 20_000, 1)),
                Format.XLSX, new SheetImportBuilder.Options(null, 2, RowsMode.FIXED, null, true));

        SheetDocument.Tab tab = imported.checked().document().sheets().get(0);
        assertThat(tab.inputs()).hasSize(1);
        assertThat(tab.body().cells().values()).extracting(SheetGrid.Cell::value).doesNotContain("Yes / No");
    }
}
