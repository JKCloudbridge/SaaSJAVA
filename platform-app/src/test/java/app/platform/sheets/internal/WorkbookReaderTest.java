package app.platform.sheets.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * The reader of a workbook, with no database (SH1B, ADR-0253): what it takes of the look of a cell (a font family
 * mapped to a key, colours as {@code #rrggbb} with the theme and the tint applied, borders, wrapping, number formats),
 * the worksheet it reads, the panes, the page set-up and the conditional colours, the links, and the check boxes it
 * places in cells. The hostile files are in {@link SpreadsheetCorpusTest} and {@link ExtrasHostileTest}.
 */
class WorkbookReaderTest {

    private static final ImportProperties SETTINGS = ImportProperties.standard();
    private static final SheetProperties.Limits LIMITS = SheetProperties.Limits.standard();

    private static RawSheet read(byte[] file) {
        return new WorkbookReader(SETTINGS, LIMITS).read(file, null);
    }

    private static RawSheet.Cell cell(RawSheet sheet, int row, int column) {
        return sheet.rows.get(row).get(column);
    }

    // ---- the look of a cell ----

    @Test
    void aFontFamilyBecomesAKeyAndTheColourIsResolvedFromTheTheme() {
        RawSheet sheet = read(RichWorkbook.file().row(1, XlsxFile.text("A1", "Heading", RichWorkbook.HEADING)).bytes());

        StyleBook.Format format = cell(sheet, 0, 0).format();

        assertThat(format.bold()).isTrue();
        assertThat(format.points()).isEqualTo(14);
        assertThat(format.look().font()).isEqualTo("SANS_NEUTRAL");
        assertThat(format.look().size()).isEqualTo(14);
        assertThat(format.look().colour()).isEqualTo("#4472c4");
        assertThat(format.fontFallback()).isFalse();
    }

    @Test
    void anUnknownFamilyTakesTheGenericKeyAndIsCountedOnceWhateverHowManyCellsUseIt() {
        RawSheet sheet = read(RichWorkbook.file().row(1, XlsxFile.text("A1", "One", RichWorkbook.UNKNOWN_FONT),
                XlsxFile.text("B1", "Two", RichWorkbook.UNKNOWN_FONT)).bytes());

        StyleBook.Format format = cell(sheet, 0, 0).format();

        assertThat(format.look().font()).isEqualTo("SANS");
        assertThat(format.fontFallback()).isTrue();
        assertThat(sheet.notes.of(ImportNote.FONT_FALLBACK)).isEqualTo(1);
    }

    @Test
    void borderStylesAlignmentWrappingAndUnderlineAreMappedToTheClosedLists() {
        RawSheet sheet = read(RichWorkbook.file().row(1, XlsxFile.text("A1", "x", RichWorkbook.UNKNOWN_FONT)).bytes());

        StyleBook.Format format = cell(sheet, 0, 0).format();

        assertThat(format.italic()).isTrue();
        assertThat(format.underline()).isTrue();
        assertThat(format.horizontal()).isEqualTo("CENTER");
        assertThat(format.look().wrap()).isTrue();
        assertThat(format.look().verticalAlignment()).isEqualTo("MIDDLE");
        assertThat(format.look().colour()).isEqualTo("#c00000");
        assertThat(format.look().size()).isEqualTo(9);
        assertThat(format.look().borders()).isEqualTo(new SheetLook.Borders("DOUBLE", "MEDIUM", null, "THIN"));
        assertThat(format.bordered()).isTrue();
    }

    @Test
    void aBlankCellWithAFillAndABorderKeepsItsLookAndIsPartOfTheTable() {
        RawSheet sheet = read(RichWorkbook.file().row(1, XlsxFile.text("A1", "h"),
                "<c r=\"B1\" s=\"" + RichWorkbook.YELLOW_BOX + "\"/>").bytes());

        StyleBook.Format format = sheet.blankLooks.get(0).get(1);

        assertThat(format.look().fill()).isEqualTo("#ffff00");
        assertThat(format.look().borders()).isEqualTo(new SheetLook.Borders("THIN", "THIN", "THIN", "THIN"));
        assertThat(sheet.bordered.get(0).get(1)).isTrue();
    }

    @Test
    void aThemeColourWithATintIsLighterThanItsBaseColour() {
        RawSheet sheet = read(RichWorkbook.file().row(1, XlsxFile.text("A1", "x", RichWorkbook.PLAIN),
                "<c r=\"B1\" s=\"" + RichWorkbook.TINTED + "\"/>").bytes());

        String fill = sheet.blankLooks.get(0).get(1).look().fill();

        assertThat(fill).matches("#[0-9a-f]{6}").isNotEqualTo("#4472c4");
        int base = Integer.parseInt("4472c4".substring(0, 2), 16);
        int lighter = Integer.parseInt(fill.substring(1, 3), 16);
        assertThat(lighter).as("the red channel moves toward white").isGreaterThan(base);
    }

    @Test
    void numberFormatsAreMappedAndAnUnknownOneIsCountedWithTheNumberKept() {
        RawSheet sheet = read(RichWorkbook.file().row(1, XlsxFile.number("A1", "0.5", RichWorkbook.PERCENT),
                XlsxFile.number("B1", "0.25", RichWorkbook.MONO_PERCENT), XlsxFile.number("C1", "12.5",
                        RichWorkbook.TINTED)).bytes());

        assertThat(cell(sheet, 0, 0).format().look().numberFormat()).isEqualTo("PERCENT");
        StyleBook.Format mono = cell(sheet, 0, 1).format();
        assertThat(mono.look().numberFormat()).isEqualTo("PERCENT_TWO_DECIMALS");
        assertThat(mono.look().font()).isEqualTo("MONO_CLASSIC");
        assertThat(mono.look().colour()).as("plain black is what the page already draws").isNull();
        assertThat(cell(sheet, 0, 2).format().unknownNumberFormat()).isTrue();
        assertThat(cell(sheet, 0, 2).text()).isEqualTo("12.5");
        assertThat(sheet.notes.of(ImportNote.NUMBER_FORMAT_UNKNOWN)).isEqualTo(1);
    }

    @Test
    void aFormulaComesInAsItsLastValueAndALongOneBelowTheLimitIsAccepted() {
        RawSheet sheet = read(new XlsxFile().row(1, XlsxFile.formula("A1", "1+".repeat(1_999) + "1", "2000")).bytes());

        assertThat(cell(sheet, 0, 0).text()).isEqualTo("2000");
        assertThat(sheet.notes.of(ImportNote.FORMULAS)).isEqualTo(1);
    }

    // ---- the worksheet ----

    @Test
    void theFirstVisibleWorksheetThatHoldsAnythingIsReadAndHiddenOnesAreSkippedAndCounted() {
        byte[] file = new XlsxFile()
                .extraSheet("Notes", "hidden", XlsxFile.sheetXml("<row r=\"1\">" + XlsxFile.text("A1", "secret")
                        + "</row>"))
                .extraSheet("Data", null, XlsxFile.sheetXml("<row r=\"1\">" + XlsxFile.text("A1", "found") + "</row>"))
                .bytes();

        RawSheet sheet = read(file);

        assertThat(sheet.worksheets).extracting(RawSheet.Worksheet::title).containsExactly("One", "Data");
        assertThat(sheet.worksheet).isEqualTo(1);
        assertThat(sheet.title).isEqualTo("Data");
        assertThat(cell(sheet, 0, 0).text()).isEqualTo("found");
        assertThat(sheet.notes.of(ImportNote.HIDDEN_SHEETS)).isEqualTo(1);
        assertThat(sheet.notes.of(ImportNote.OTHER_SHEETS)).isEqualTo(1);
    }

    @Test
    void aChosenWorksheetIsTheOneReadAndAnUnknownChoiceIsRefused() {
        byte[] file = new XlsxFile().row(1, XlsxFile.text("A1", "first"))
                .extraSheet("Data", null, XlsxFile.sheetXml("<row r=\"1\">" + XlsxFile.text("A1", "second") + "</row>"))
                .bytes();
        WorkbookReader reader = new WorkbookReader(SETTINGS, LIMITS);

        RawSheet second = reader.read(file, 1);

        assertThat(cell(second, 0, 0).text()).isEqualTo("second");
        assertThat(second.worksheet).isEqualTo(1);
        assertThatThrownBy(() -> reader.read(file, 2)).isInstanceOfSatisfying(ImportRefusal.class,
                refusal -> assertThat(refusal.code()).isEqualTo("no_such_worksheet"));
        assertThatThrownBy(() -> reader.read(file, -1)).isInstanceOfSatisfying(ImportRefusal.class,
                refusal -> assertThat(refusal.code()).isEqualTo("no_such_worksheet"));
    }

    @Test
    void theTitleOfATabIsOfferedOnlyWhenItCanBeUsedAsATitle() {
        assertThat(WorkbookReader.titleOf("Plant activity")).isEqualTo("Plant activity");
        assertThat(WorkbookReader.titleOf("x".repeat(32))).isNull();
        assertThat(WorkbookReader.titleOf("<b>x</b>")).isNull();
        assertThat(WorkbookReader.titleOf("a/b")).isNull();
        assertThat(WorkbookReader.titleOf("  ")).isNull();
        assertThat(WorkbookReader.titleOf(null)).isNull();
    }

    // ---- panes, page set-up and conditional colours ----

    @Test
    void aFrozenPaneAndThePageSetupAreRead() {
        byte[] file = new XlsxFile()
                .beforeData("<sheetPr><pageSetUpPr fitToPage=\"1\"/></sheetPr><sheetViews><sheetView "
                        + "workbookViewId=\"0\"><pane xSplit=\"1\" ySplit=\"2\" topLeftCell=\"B3\" "
                        + "activePane=\"bottomRight\" state=\"frozen\"/></sheetView></sheetViews>")
                .afterMerges("<pageSetup paperSize=\"8\" orientation=\"landscape\" fitToWidth=\"1\" "
                        + "fitToHeight=\"0\"/>")
                .row(1, XlsxFile.text("A1", "x")).bytes();

        RawSheet sheet = read(file);

        assertThat(sheet.freezeRows).isEqualTo(2);
        assertThat(sheet.freezeColumns).isEqualTo(1);
        assertThat(sheet.print).isEqualTo(new RawSheet.Print("A3", "LANDSCAPE", true, false));
    }

    @Test
    void aConditionalColourThatComparesAValueWithANumberIsKeptAndAnyOtherKindIsCounted() {
        byte[] file = RichWorkbook.file().row(1, XlsxFile.text("A1", "x"), XlsxFile.number("B1", "0.5", 0))
                .afterMerges("<conditionalFormatting sqref=\"B2:B10 D2\"><cfRule type=\"cellIs\" dxfId=\"0\" "
                        + "priority=\"1\" "
                                + "operator=\"lessThan\"><formula>0.85</formula></cfRule></conditionalFormatting>"
                        + "<conditionalFormatting sqref=\"B2:B10\"><cfRule type=\"expression\" "
                                + "dxfId=\"1\" priority=\"2\">"
                        + "<formula>B2&gt;1</formula></cfRule></conditionalFormatting>"
                        + "<conditionalFormatting sqref=\"C2\"><cfRule type=\"cellIs\" dxfId=\"1\" priority=\"3\" "
                        + "operator=\"between\"><formula>1</formula><formula>A1</formula></cfRule>"
                        + "</conditionalFormatting>")
                .bytes();

        RawSheet sheet = read(file);

        assertThat(sheet.rules).containsExactly(
                new RawSheet.Rule(1, 1, 9, 1, "LT", "0.85", null, "#ffc7ce", "#9c0006"),
                new RawSheet.Rule(1, 3, 1, 3, "LT", "0.85", null, "#ffc7ce", "#9c0006"));
        assertThat(sheet.notes.of(ImportNote.CONDITIONS_LEFT_OUT)).as("an expression and a reference").isEqualTo(2);
    }

    // ---- links ----

    @Test
    void anHttpsLinkIsKeptOnItsCellAndALinkInsideTheWorkbookIsCounted() {
        byte[] file = new XlsxFile().row(1, XlsxFile.text("A1", "see"), XlsxFile.text("B1", "there"))
                .afterMerges("<hyperlinks><hyperlink ref=\"A1\" r:id=\"rId1\"/><hyperlink ref=\"B1\" "
                        + "location=\"Other!A1\"/></hyperlinks>")
                .part("xl/worksheets/_rels/sheet1.xml.rels",
                        ("<Relationships xmlns=\"http://schemas.openxmlformats.org/"
                        + "package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas."
                        + "openxmlformats.org/officeDocument/2006/relationships/hyperlink\" "
                        + "Target=\"https://example.test/page\" TargetMode=\"External\"/></Relationships>")
                        .getBytes(StandardCharsets.UTF_8))
                .bytes();

        RawSheet sheet = read(file);

        assertThat(sheet.links).containsValue("https://example.test/page").hasSize(1);
        assertThat(sheet.notes.of(ImportNote.LINKS_INSIDE)).isEqualTo(1);
    }
}
