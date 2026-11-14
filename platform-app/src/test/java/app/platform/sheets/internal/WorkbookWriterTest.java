package app.platform.sheets.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.platform.sheets.internal.SheetDocument.Tab;
import app.platform.sheets.internal.SheetGrid.Alignment;
import app.platform.sheets.internal.SheetGrid.Cell;
import app.platform.sheets.internal.SheetGrid.Colour;
import app.platform.sheets.internal.SheetGrid.Column;
import app.platform.sheets.internal.SheetGrid.ColumnType;
import app.platform.sheets.internal.SheetGrid.FontSize;
import app.platform.sheets.internal.SheetGrid.Kind;
import app.platform.sheets.internal.SheetGrid.Line;
import app.platform.sheets.internal.SheetGrid.Merge;
import app.platform.sheets.internal.SheetGrid.RowsMode;
import app.platform.sheets.internal.SheetGrid.Style;
import app.platform.sheets.internal.WorkbookWriter.Page;
import app.platform.sheets.internal.WorkbookWriter.Written;
import app.platform.sheets.internal.formula.FormulaLimits;
import app.platformapi.ApiException;
import app.platformapi.ErrorCode;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.StringLength;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.Hyperlink;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The Excel writer, with no database (FA7, ADR-0201; SH3, ADR-0253). The file is read back by the reader of the
 * spreadsheet upload and, in these tests only, by the library's whole-workbook reader, to prove that it is a
 * well-formed workbook: the same cells, merges, widths and heights; a text that a spreadsheet program could take for
 * a formula is written as text; the footer row is there with or without a text; the input cells of a template are
 * empty; the page is landscape for a wide sheet. For a sheet in format version 2 everything it states reaches the
 * file: the look, the formulas with their answers beside them, the conditional colours, the frozen pane and the page
 * set-up.
 */
class WorkbookWriterTest {

    private static final SheetProperties.Limits LIMITS = SheetProperties.Limits.standard();
    private final WorkbookWriter writer = new WorkbookWriter(ExportProperties.standard());

    private static Column column(String id, String label, Integer width) {
        return new Column(id, label, ColumnType.TEXT, false, null, null, width, false);
    }

    private static SheetGrid grid(int dimension, List<Column> columns, List<Line> headers, List<Line> rows,
            Map<String, Cell> cells, List<Merge> merges) {
        return new SheetGrid(dimension, dimension == 2 ? RowsMode.FIXED : RowsMode.REPEATABLE, columns, headers, rows,
                cells, merges);
    }

    /** A sheet with a merged bold title, a fixed body cell, an input cell that holds a default, and wide columns. */
    private static SheetGrid sample() {
        Map<String, Cell> cells = new LinkedHashMap<>();
        cells.put("h1:a", new Cell("Checklist", new Style(true, false, FontSize.LARGE, false, Alignment.CENTER,
                Colour.ACCENT), null));
        cells.put("r1:a", new Cell("Door", null, Kind.FIXED));
        cells.put("r1:b", new Cell("typed by a filler", null, Kind.INPUT));
        return grid(2, List.of(column("a", "Finding", 200), column("b", "Result", null)),
                List.of(new Line("h1", null, 40)), List.of(new Line("r1", "First", null)), cells,
                List.of(new Merge("h1:a", "h1:b")));
    }

    private byte[] write(SheetGrid grid, String footer) {
        return writer.write(List.of(Page.one(grid, footer)), null).bytes();
    }

    private static RawSheet read(byte[] file) {
        return new WorkbookReader(ImportProperties.standard(), LIMITS).read(file, null);
    }

    private static String part(byte[] file, String name) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(file))) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (entry.getName().equals(name)) {
                    return new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        throw new AssertionError("no part " + name);
    }

    private static List<String> partNames(byte[] file) throws IOException {
        List<String> names = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(file))) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                names.add(entry.getName());
            }
        }
        return names;
    }

    /** The library's whole-workbook reader, used in tests only, to prove the file is well formed. */
    private static XSSFWorkbook open(byte[] file) throws IOException {
        return new XSSFWorkbook(new ByteArrayInputStream(file));
    }

    private static String text(RawSheet sheet, int row, int column) {
        RawSheet.Cell cell = sheet.rows.getOrDefault(row, new TreeMap<>()).get(column);
        return cell == null ? null : cell.text();
    }

    // ---- a sheet in format version 1: what it always was ----

    @Test
    void theReaderOfTheUploadReadsTheFileBackToTheSameCellsMergesWidthsAndHeights() {
        RawSheet back = read(write(sample(), "F/MR/16  Rev No.'00'"));

        assertThat(text(back, 0, 0)).as("the band of column labels").isEqualTo("Finding");
        assertThat(text(back, 0, 1)).isEqualTo("Result");
        assertThat(text(back, 1, 0)).as("the header row").isEqualTo("Checklist");
        assertThat(back.rows.get(1).get(0).bold()).isTrue();
        assertThat(text(back, 2, 0)).as("the body row").isEqualTo("Door");
        assertThat(text(back, 2, 1)).as("an input cell is written empty").isNull();
        assertThat(back.merges).containsExactly(new RawSheet.Area(1, 0, 1, 1));
        assertThat(back.heights.get(1)).as("the height of the header row").isBetween(38, 42);
        assertThat(back.widths).anySatisfy(width -> {
            assertThat(width.first()).isZero();
            assertThat(width.pixels()).as("the width of the first column").isBetween(195, 205);
        });
    }

    @Test
    void theFooterRowIsTheLastRowAcrossTheFullWidthWithItsTextAtTheRightOfTheLastColumn() throws IOException {
        byte[] file = write(sample(), "F/MR/16  Rev No.'01'");
        RawSheet back = read(file);

        assertThat(text(back, 3, 1)).isEqualTo("F/MR/16  Rev No.'01'");
        assertThat(text(back, 3, 0)).isNull();
        assertThat(part(file, "xl/worksheets/sheet1.xml")).contains("<dimension ref=\"A1:B4\"/>")
                .contains("<c r=\"A4\" s=").contains("<c r=\"B4\" s=");
        assertThat(part(file, "xl/styles.xml")).as("every cell is bordered").contains("<left style=\"thin\"");
    }

    @Test
    void theFooterRowIsThereAndBlankBeforeTheTemplateIsNumbered() throws IOException {
        byte[] file = write(sample(), "");
        RawSheet back = read(file);

        assertThat(part(file, "xl/worksheets/sheet1.xml")).contains("<dimension ref=\"A1:B4\"/>");
        assertThat(back.rows.keySet()).as("no text in the footer row").containsExactly(0, 1, 2);
    }

    @Test
    void aSheetWhoseFillerAddsTheRowsShowsTheSameBlankSampleRowsAsTheScreen() throws IOException {
        SheetGrid grid = grid(1, List.of(column("a", "Finding", null)), List.of(new Line("h1", null, null)), List.of(),
                Map.of("h1:a", new Cell("Title", null, null)), List.of());

        assertThat(part(write(grid, ""), "xl/worksheets/sheet1.xml")).contains("<dimension ref=\"A1:A13\"/>");
    }

    @Test
    void aSheetInFormatOneStaysPlainInTheFile() throws IOException {
        byte[] file = write(sample(), "");
        String styles = part(file, "xl/styles.xml");

        assertThat(styles).as("the page's own font only").contains("Calibri").doesNotContain("<fill><patternFill "
                + "patternType=\"solid\"");
        assertThat(part(file, "xl/worksheets/sheet1.xml")).doesNotContain("<dataValidation")
                .doesNotContain("conditionalFormatting").doesNotContain("<pane").doesNotContain("<hyperlink");
    }

    @Test
    void theFourNamedColoursAreWrittenAsTheirExactValues() throws IOException {
        Map<String, Cell> cells = new LinkedHashMap<>();
        Colour[] colours = {Colour.ACCENT, Colour.SUCCESS, Colour.DANGER};
        for (int i = 0; i < colours.length; i++) {
            cells.put("r" + i + ":a", new Cell("x", new Style(false, false, FontSize.NORMAL, false, null, colours[i]),
                    Kind.FIXED));
        }
        List<Line> rows = List.of(new Line("r0", null, null), new Line("r1", null, null), new Line("r2", null, null));
        byte[] file = write(grid(2, List.of(column("a", "A", null)), List.of(), rows, cells, List.of()), "");

        String styles = part(file, "xl/styles.xml").toUpperCase(java.util.Locale.ROOT);
        assertThat(styles).contains("FF93418B").contains("FF1E7B34").contains("FFB3261E");
    }

    // ---- what a spreadsheet program must never run ----

    @Test
    void aTextThatLooksLikeAFormulaIsWrittenAsTextWithTheQuotePrefixAndNoFormulaExists() throws IOException {
        Map<String, Cell> cells = new LinkedHashMap<>();
        List<String> hostile = List.of("=1+1", "+cmd|' /C calc'!A0", "-2+3", "@SUM(A1:A9)", "=HYPERLINK(\"http://x\")");
        List<Line> rows = new ArrayList<>();
        for (int i = 0; i < hostile.size(); i++) {
            rows.add(new Line("r" + i, null, null));
            cells.put("r" + i + ":a", new Cell(hostile.get(i), null, Kind.FIXED));
        }
        byte[] file = write(grid(2, List.of(column("a", "Text", null)), List.of(), rows, cells, List.of()), "");

        String sheet = part(file, "xl/worksheets/sheet1.xml");
        assertThat(sheet).doesNotContain("<f>").doesNotContain("<f ").doesNotContain("<hyperlink");
        assertThat(part(file, "xl/styles.xml")).contains("quotePrefix=\"true\"");
        RawSheet back = read(file);
        for (int i = 0; i < hostile.size(); i++) {
            assertThat(text(back, i + 1, 0)).as("the text comes back as it was typed").isEqualTo(hostile.get(i));
        }
        assertThat(back.formulas).isEmpty();
        try (XSSFWorkbook workbook = open(file)) {
            for (int i = 0; i < hostile.size(); i++) {
                XSSFCell cell = workbook.getSheetAt(0).getRow(i + 1).getCell(0);
                assertThat(cell.getCellType()).isEqualTo(org.apache.poi.ss.usermodel.CellType.STRING);
                assertThat(cell.getCellStyle().getQuotePrefixed()).isTrue();
            }
        }
    }

    @Test
    void aSheetWhoseFixedTextsAndLabelsAreFormulasHoldsNoFormula() throws IOException {
        Map<String, Cell> cells = Map.of("h1:a", new Cell("=A1+1", null, null), "r1:a",
                new Cell("+SUM(1,2)", null, Kind.FIXED));
        byte[] file = write(grid(2, List.of(column("a", "=HYPERLINK(\"http://x\",\"y\")", null), column("b",
                "@A1", null)), List.of(new Line("h1", null, null)), List.of(new Line("r1", null, null)), cells,
                List.of()), "-1");

        assertThat(part(file, "xl/worksheets/sheet1.xml")).doesNotContain("<f>").doesNotContain("<f ");
        RawSheet back = read(file);
        assertThat(back.formulas).isEmpty();
        assertThat(text(back, 0, 0)).isEqualTo("=HYPERLINK(\"http://x\",\"y\")");
        assertThat(text(back, 0, 1)).isEqualTo("@A1");
        assertThat(text(back, 3, 1)).isEqualTo("-1");
    }

    @Test
    void markupAndControlCharactersInATextAreEscapedOrDroppedAndTheWorkbookStaysValid() throws IOException {
        Map<String, Cell> cells = Map.of("r1:a", new Cell("a < b & \"c\" > d\u0001e", null, Kind.FIXED));
        byte[] file = write(grid(2, List.of(column("a", "T</t>", null)), List.of(), List.of(new Line("r1", null, null)),
                cells, List.of()), "");

        String strings = part(file, "xl/sharedStrings.xml");
        assertThat(strings).contains("a &lt; b &amp;").contains("T&lt;/t>");
        assertThat(strings).doesNotContain("\u0001");
        assertThat(text(read(file), 1, 0)).isEqualTo("a < b & \"c\" > de");
        open(file).close();
    }

    @Test
    void aVeryLongTextIsCutAtTheLimitOfACellAndTheWorkbookStaysValid() throws IOException {
        // Letters drawn at random, so the text does not compress to nothing (the library refuses a "zip bomb").
        java.util.Random random = new java.util.Random(7);
        StringBuilder long40k = new StringBuilder();
        for (int i = 0; i < 40_000; i++) {
            long40k.append((char) ('a' + random.nextInt(26)));
        }
        Map<String, Cell> cells = Map.of("r1:a", new Cell(long40k.toString(), null, Kind.FIXED));
        byte[] file = write(grid(2, List.of(column("a", "T", null)), List.of(), List.of(new Line("r1", null, null)),
                cells, List.of()), "");

        try (XSSFWorkbook workbook = open(file)) {
            assertThat(workbook.getSheetAt(0).getRow(1).getCell(0).getStringCellValue()).hasSize(32_767);
        }
    }

    @Test
    void theWorkbookHoldsOnlyTheSixPartsAWorkbookNeedsAndTheInformationAndNoMacroLinkOrImage() throws IOException {
        byte[] file = write(sample(), "x");
        List<String> names = partNames(file);

        assertThat(names).allSatisfy(name -> assertThat(name).doesNotContain("vba").doesNotContain("drawing")
                .doesNotContain("media").doesNotContain("embeddings").doesNotContain("externalLinks")
                .doesNotContain("comments").doesNotContain("printerSettings").doesNotContain("activeX")
                .doesNotContain("calcChain"));
        assertThat(names).contains("[Content_Types].xml", "_rels/.rels", "xl/workbook.xml", "xl/styles.xml",
                "xl/worksheets/sheet1.xml");
        assertThat(part(file, "xl/worksheets/sheet1.xml")).doesNotContain("<hyperlink");
    }

    @Property(tries = 60)
    void whateverTextIsInACellTheFileIsAWorkbookTheReaderAcceptsAndHoldsNoFormula(
            @ForAll @StringLength(max = 60) String typed) throws IOException {
        Map<String, Cell> cells = Map.of("r1:a", new Cell(typed.isEmpty() ? null : typed, null, Kind.FIXED));
        byte[] file = write(grid(2, List.of(column("a", "Label", null)), List.of(), List.of(new Line("r1", null, null)),
                cells, List.of()), typed);

        assertThat(part(file, "xl/worksheets/sheet1.xml")).doesNotContain("<f>").doesNotContain("<f ");
        assertThat(read(file).formulas).isEmpty();
        open(file).close();
    }

    // ---- the page ----

    @Test
    void aWideSheetIsPrintedLandscapeAndANarrowOneIsNot() throws IOException {
        SheetGrid wide = grid(2, List.of(column("a", "A", 400), column("b", "B", 400)), List.of(), List.of(),
                Map.of(), List.of());
        SheetGrid narrow = grid(2, List.of(column("a", "A", 200)), List.of(), List.of(), Map.of(), List.of());

        assertThat(PrintRules.wide(wide)).isTrue();
        assertThat(PrintRules.wide(narrow)).isFalse();
        assertThat(part(write(wide, ""), "xl/worksheets/sheet1.xml")).contains("orientation=\"landscape\"")
                .contains("fitToWidth=\"1\"").contains("paperSize=\"9\"");
        assertThat(part(write(narrow, ""), "xl/worksheets/sheet1.xml")).contains("orientation=\"portrait\"");
    }

    @Test
    void theColumnLabelsAndTheHeaderRowsRepeatOnEveryPrintedPage() throws IOException {
        assertThat(part(write(sample(), ""), "xl/workbook.xml")).contains("_xlnm.Print_Titles")
                .contains("Sheet1!$1:$2");
    }

    @Test
    void aPageOfItsOwnHasNoBandNoFooterAndNoRepeatedRows() throws IOException {
        byte[] file = writer.write(List.of(new Page(null, sample(), null, null, "x", true)), null).bytes();

        assertThat(part(file, "xl/worksheets/sheet1.xml")).contains("<dimension ref=\"A1:B2\"/>");
        assertThat(part(file, "xl/workbook.xml")).doesNotContain("Print_Titles");
    }

    @Test
    void aReferenceIsWrittenAsALetterAndARow() {
        assertThat(WorkbookWriter.ref(1, 1)).isEqualTo("A1");
        assertThat(WorkbookWriter.ref(26, 9)).isEqualTo("Z9");
        assertThat(WorkbookWriter.ref(27, 10)).isEqualTo("AA10");
        assertThat(WorkbookWriter.looksLikeFormula("=a")).isTrue();
        assertThat(WorkbookWriter.looksLikeFormula("a=")).isFalse();
        assertThat(WorkbookWriter.looksLikeFormula("")).isFalse();
    }

    // ---- a sheet in format version 2 ----

    private static List<Page> pages(Map<String, Object> document, String footer) {
        SheetGridValidator.Result checked = FormulaDocs.VALIDATOR.check(GridDocs.bytes(document));
        assertThat(checked.problems()).as("the test document is a valid sheet").isEmpty();
        JsonNode stored = GridDocs.JSON.readTree(GridDocs.bytes(document));
        return DefaultSheetExports.pagesOf(stored, footer, FormulaLimits.DEFAULTS);
    }

    private Written writeTwo(Map<String, Object> document) {
        return writer.write(pages(document, "F/MR/16  Rev No.'00'"), "Made by the platform.");
    }

    private static Map<String, Object> look(Object... pairs) {
        return GridDocs.map("look", GridDocs.map(pairs));
    }

    @Test
    void theLookOfACellReachesTheFileAndComesBackAsTheSameLook() throws IOException {
        Map<String, Object> document = FormulaDocs.named();
        TwoDocs.cells(document).put("h1:c1", GridDocs.map("value", "Item", "style", GridDocs.map("bold", true,
                "alignment", "CENTER"), "look", GridDocs.map("font", "SERIF_TEXT", "size", 14, "fill", "#ffeecc",
                "colour", "#112233", "borders", GridDocs.map("top", "THIN", "bottom", "DOUBLE"), "wrap", true,
                "verticalAlignment", "TOP", "numberFormat", "TWO_DECIMALS")));
        byte[] file = writeTwo(document).bytes();

        try (XSSFWorkbook workbook = open(file)) {
            XSSFCell cell = workbook.getSheetAt(0).getRow(1).getCell(0);
            XSSFCellStyle style = cell.getCellStyle();
            assertThat(style.getFont().getFontName()).isEqualTo("Times New Roman");
            assertThat(style.getFont().getFontHeightInPoints()).isEqualTo((short) 14);
            assertThat(style.getFont().getBold()).isTrue();
            assertThat(style.getFillForegroundXSSFColor().getARGBHex()).endsWith("FFEECC");
            assertThat(style.getFont().getXSSFColor().getARGBHex()).endsWith("112233");
            assertThat(style.getBorderTop()).isEqualTo(BorderStyle.THIN);
            assertThat(style.getBorderBottom()).isEqualTo(BorderStyle.DOUBLE);
            assertThat(style.getBorderLeft()).isEqualTo(BorderStyle.NONE);
            assertThat(style.getWrapText()).isTrue();
            assertThat(style.getAlignment()).isEqualTo(org.apache.poi.ss.usermodel.HorizontalAlignment.CENTER);
            assertThat(style.getVerticalAlignment()).isEqualTo(org.apache.poi.ss.usermodel.VerticalAlignment.TOP);
            assertThat(style.getDataFormatString()).isEqualTo("0.00");
        }
        SheetLook back = read(file).rows.get(1).get(0).format().look();
        assertThat(back).isEqualTo(new SheetLook("SERIF_TEXT", 14, "#112233", "#ffeecc", true, "TOP",
                new SheetLook.Borders("THIN", null, "DOUBLE", null), "TWO_DECIMALS"));
    }

    @Test
    void everyFontKeyIsWrittenAsOneFixedFamilyAndComesBackAsTheSameKey() throws IOException {
        Map<String, Object> document = FormulaDocs.named();
        List<Map<String, Object>> columns = new ArrayList<>();
        Map<String, Object> cells = TwoDocs.cells(document);
        TwoDocs.sheet(document).put("columns", columns);
        for (int i = 0; i < SheetLook.FONTS.size(); i++) {
            columns.add(GridDocs.column("k" + i, "Label " + i, "text"));
            cells.put("h1:k" + i, GridDocs.map("value", "x", "look", GridDocs.map("font", SheetLook.FONTS.get(i))));
        }
        RawSheet back = read(writeTwo(document).bytes());

        for (int i = 0; i < SheetLook.FONTS.size(); i++) {
            assertThat(back.rows.get(1).get(i).format().look().font()).isEqualTo(SheetLook.FONTS.get(i));
            assertThat(FontKeys.of(FontFamilies.of(SheetLook.FONTS.get(i))).key()).isEqualTo(SheetLook.FONTS.get(i));
        }
        assertThat(FontFamilies.of(null)).isEqualTo("Calibri");
        assertThat(FontFamilies.of("<script>")).isEqualTo("Calibri");
    }

    @Test
    void everyNumberFormatKeyIsWrittenAsOneCodeAndComesBackAsTheSameKey() {
        Map<String, Object> document = FormulaDocs.named();
        List<Map<String, Object>> columns = new ArrayList<>();
        Map<String, Object> cells = TwoDocs.cells(document);
        TwoDocs.sheet(document).put("columns", columns);
        List<String> keys = SheetLook.NUMBER_FORMATS.stream().filter(key -> !"GENERAL".equals(key)).toList();
        for (int i = 0; i < keys.size(); i++) {
            columns.add(GridDocs.column("n" + i, "Label " + i, "text"));
            cells.put("h1:n" + i, GridDocs.map("value", "x", "look", GridDocs.map("numberFormat", keys.get(i))));
        }
        RawSheet back = read(writeTwo(document).bytes());

        for (int i = 0; i < keys.size(); i++) {
            assertThat(back.rows.get(1).get(i).format().look().numberFormat()).as(keys.get(i)).isEqualTo(keys.get(i));
        }
    }

    @Test
    void aFormulaIsWrittenAsAFormulaWithItsCalculatedAnswerBesideIt() throws IOException {
        Map<String, Object> document = FormulaDocs.named();
        FormulaDocs.withCell(document, "r3:c1", "SUM(@{c1,r1}:@{c1,r2})+4");
        FormulaDocs.withCell(document, "r3:c2", "IF(2>1,\"big\",\"small\")");
        FormulaDocs.withCell(document, "r1:c2", "1/0");
        Written written = writeTwo(document);

        assertThat(written.formulas()).isEqualTo(3);
        assertThat(written.formulasAsAnswers()).isZero();
        RawSheet back = read(written.bytes());
        assertThat(back.formulas.get(4).get(0).text()).isEqualTo("SUM(A3:A4)+4");
        assertThat(back.formulas.get(4).get(1).text()).isEqualTo("IF(2>1,\"big\",\"small\")");
        assertThat(back.typed.get(4).get(0)).as("the answer stored beside the formula").isEqualTo(4.0);
        assertThat(back.typed.get(4).get(1)).isEqualTo("big");
        assertThat(back.typed.get(2).get(1)).as("an error is its code").isInstanceOf(
                app.platform.sheets.internal.formula.XlError.class);
        try (XSSFWorkbook workbook = open(written.bytes())) {
            XSSFCell sum = workbook.getSheetAt(0).getRow(4).getCell(0);
            assertThat(sum.getCellFormula()).isEqualTo("SUM(A3:A4)+4");
            assertThat(sum.getNumericCellValue()).isEqualTo(4.0);
        }
    }

    @Test
    void everyCachedAnswerIsTheAnswerOfTheServersCalculation() throws IOException {
        Map<String, Object> document = FormulaDocs.repeating();
        FormulaDocs.withColumn(document, "c2", "@{c1}*2");
        Tab tab = FormulaDocs.accepted(document);
        SheetCalculator.Outcome outcome = SheetCalculator.calculate(tab, SheetCalculator.Inputs.emptyRows(
                WorkbookWriter.SAMPLE_ROWS), FormulaLimits.DEFAULTS);
        Written written = writeTwo(document);

        assertThat(written.formulas()).as("one formula at every sample row").isEqualTo(WorkbookWriter.SAMPLE_ROWS);
        try (XSSFWorkbook workbook = open(written.bytes())) {
            XSSFSheet sheet = workbook.getSheetAt(0);
            for (int i = 0; i < WorkbookWriter.SAMPLE_ROWS; i++) {
                XSSFCell cell = sheet.getRow(2 + i).getCell(1);
                assertThat(cell.getCellFormula()).isEqualTo("A" + (3 + i) + "*2");
                SheetCalculator.Answer answer = outcome.columns().get("c2").get(i);
                assertThat(answer.kind()).isEqualTo(SheetCalculator.Kind.NUMBER);
                assertThat(cell.getNumericCellValue()).isEqualTo(Double.parseDouble(answer.value()));
            }
        }
    }

    @Test
    void aGrowingRangeIsDrawnOverTheSampleRows() {
        Map<String, Object> document = FormulaDocs.repeating();
        FormulaDocs.withCell(document, "h1:c2", "SUM(@{c1,*})");
        Written written = writeTwo(document);

        RawSheet back = read(written.bytes());
        assertThat(back.formulas.get(1).get(1).text()).isEqualTo("SUM(A3:A12)");
    }

    @Test
    void theConditionalColoursFrozenPaneAndPageSetUpAreWritten() throws IOException {
        Map<String, Object> document = FormulaDocs.named();
        Map<String, Object> sheet = TwoDocs.sheet(document);
        sheet.put("freeze", GridDocs.map("rows", 2, "columns", 1));
        sheet.put("print", GridDocs.map("paper", "A3", "orientation", "LANDSCAPE", "fitToWidth", true,
                "repeatHeaderRows", false));
        sheet.put("conditions", new ArrayList<>(List.of(GridDocs.map("from", "r1:c1", "to", "r3:c2", "operator",
                "BETWEEN", "value", "1", "value2", "5", "fill", "#ffcccc", "colour", "#990000"))));
        byte[] file = writeTwo(document).bytes();

        RawSheet back = read(file);
        assertThat(back.freezeRows).as("the label band is row 1, then the header row and one body row").isEqualTo(3);
        assertThat(back.freezeColumns).isEqualTo(1);
        assertThat(back.print).isEqualTo(new RawSheet.Print("A3", "LANDSCAPE", true, false));
        assertThat(back.rules).containsExactly(new RawSheet.Rule(2, 0, 4, 1, "BETWEEN", "1", "5", "#ffcccc",
                "#990000"));
        assertThat(part(file, "xl/workbook.xml")).as("repeated rows were switched off").doesNotContain("Print_Titles");
    }

    @Test
    void aYesNoCellAndAPicklistCellOfATemplateGetADropDownList() throws IOException {
        Map<String, Object> document = FormulaDocs.named();
        Map<String, Object> sheet = TwoDocs.sheet(document);
        List<Map<String, Object>> columns = GridDocs.list(sheet, "columns");
        columns.set(0, GridDocs.column("c1", "Quantity", "boolean"));
        columns.set(1, GridDocs.column("c2", "Price", "picklist"));
        GridDocs.picklistValues(columns.get(1)).clear();
        GridDocs.picklistValues(columns.get(1)).addAll(List.of("Low", "High"));
        Map<String, Object> cells = TwoDocs.cells(document);
        for (String row : List.of("r1", "r2")) {
            cells.put(row + ":c1", GridDocs.map("kind", "input"));
            cells.put(row + ":c2", GridDocs.map("kind", "input"));
        }
        byte[] file = writeTwo(document).bytes();

        try (XSSFWorkbook workbook = open(file)) {
            List<? extends DataValidation> lists = workbook.getSheetAt(0).getDataValidations();
            assertThat(lists).hasSize(2);
            assertThat(lists).extracting(list -> String.join("|", list.getValidationConstraint()
                    .getExplicitListValues())).containsExactlyInAnyOrder("Yes|No", "Low|High");
            assertThat(lists).allSatisfy(list -> assertThat(list.getRegions().getCellRangeAddress(0).formatAsString())
                    .matches("[AB]3:[AB]4"));
        }
    }

    @Test
    void aListWithACommaOrOverTheLimitIsLeftOutAndCounted() {
        Map<String, Object> document = FormulaDocs.repeating();
        List<Map<String, Object>> columns = GridDocs.list(TwoDocs.sheet(document), "columns");
        columns.set(0, GridDocs.column("c1", "Quantity", "picklist"));
        columns.set(1, GridDocs.column("c2", "Price", "picklist"));
        GridDocs.picklistValues(columns.get(0)).clear();
        GridDocs.picklistValues(columns.get(0)).addAll(List.of("a,b", "c"));
        GridDocs.picklistValues(columns.get(1)).clear();
        for (int i = 0; i < 30; i++) {
            GridDocs.picklistValues(columns.get(1)).add("value-" + String.format("%03d", i));
        }

        Written written = writeTwo(document);

        assertThat(written.listsLeftOut()).isEqualTo(2);
    }

    @Test
    void onlyAnHttpsLinkOnACellWithTextIsWritten() throws IOException {
        Map<String, Object> document = FormulaDocs.named();
        TwoDocs.cells(document).put("h1:c1", GridDocs.map("value", "Guide", "link", "https://example.test/guide"));
        Written written = writeTwo(document);

        assertThat(written.links()).isEqualTo(1);
        try (XSSFWorkbook workbook = open(written.bytes())) {
            Hyperlink link = workbook.getSheetAt(0).getRow(1).getCell(0).getHyperlink();
            assertThat(link.getAddress()).isEqualTo("https://example.test/guide");
        }
        assertThat(WorkbookWriter.httpsLink("http://example.test")).isFalse();
        assertThat(WorkbookWriter.httpsLink("javascript:alert(1)")).isFalse();
        assertThat(WorkbookWriter.httpsLink("https://user:secret@example.test/")).isFalse();
        assertThat(WorkbookWriter.httpsLink("https://example.test/" + "a".repeat(2_000))).isFalse();
        assertThat(WorkbookWriter.httpsLink("https://example.test/\u0001")).isFalse();
        assertThat(WorkbookWriter.httpsLink("file:///etc/passwd")).isFalse();
    }

    @Test
    void aCellUnderAMergedAreaTakesTheEdgeSidesAndTheFillOfTheArea() throws IOException {
        Map<String, Object> document = FormulaDocs.named();
        TwoDocs.cells(document).put("h1:c1", GridDocs.map("value", "Title", "look", GridDocs.map("fill", "#ddeeff",
                "borders", GridDocs.map("top", "THIN", "right", "THIN", "bottom", "THIN", "left", "THIN"))));
        TwoDocs.sheet(document).put("merges", new ArrayList<>(List.of(GridDocs.map("from", "h1:c1", "to", "h1:c2"))));
        byte[] file = writeTwo(document).bytes();

        try (XSSFWorkbook workbook = open(file)) {
            XSSFCellStyle covered = workbook.getSheetAt(0).getRow(1).getCell(1).getCellStyle();
            assertThat(covered.getBorderRight()).isEqualTo(BorderStyle.THIN);
            assertThat(covered.getBorderLeft()).as("an inner side is not drawn").isEqualTo(BorderStyle.NONE);
            assertThat(covered.getFillForegroundXSSFColor().getARGBHex()).endsWith("DDEEFF");
        }
    }

    @Test
    void theInformationOfTheFileHoldsOnlyTheFixedWords() throws IOException {
        Map<String, Object> document = FormulaDocs.named();
        byte[] file = writer.write(pages(document, "x"), DefaultSheetExports.description(3)).bytes();

        String core = part(file, "docProps/core.xml");
        assertThat(core).contains("Made by the platform. Revision 03.");
        assertThat(DefaultSheetExports.description(0)).isEqualTo("Made by the platform. Revision 00.");
    }

    // ---- bounds ----

    @Test
    void aSheetOverTheCellBoundIsRefusedBeforeAnythingIsBuilt() {
        WorkbookWriter small = new WorkbookWriter(new ExportProperties(5_000_000, 100, 20_000, 2));
        List<Column> forty = new ArrayList<>();
        for (int c = 0; c < 40; c++) {
            forty.add(column("c" + c, "C" + c, null));
        }
        SheetGrid tooMany = grid(2, forty, List.of(), List.of(new Line("r1", null, null)), Map.of(), List.of());

        assertThatThrownBy(() -> small.write(List.of(Page.one(tooMany, "")), null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(
                        ErrorCode.PAYLOAD_TOO_LARGE));
        assertThat(small.write(List.of(Page.one(grid(2, List.of(column("a", "A", null)), List.of(), List.of(),
                Map.of(), List.of()), "")), null).bytes()).isNotEmpty();
    }

    @Test
    void aFileOverTheSizeBoundIsRefusedInTheSameWordsAsBefore() {
        WorkbookWriter small = new WorkbookWriter(new ExportProperties(1_000, 250_000, 20_000, 2));

        assertThatThrownBy(() -> small.write(List.of(Page.one(sample(), "")), null))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.PAYLOAD_TOO_LARGE);
                    assertThat(e.getMessage()).isEqualTo("The file would be larger than the allowed size of 1000 "
                            + "bytes.");
                });
    }

    @Test
    void theLargestSheetTheLimitsAllowIsWrittenWithinTheBounds() {
        List<Column> columns = new ArrayList<>();
        for (int c = 0; c < 100; c++) {
            columns.add(column("c" + c, "Column " + c, null));
        }
        List<Line> rows = new ArrayList<>();
        Map<String, Cell> cells = new LinkedHashMap<>();
        for (int r = 0; r < 497; r++) {
            rows.add(new Line("r" + r, null, null));
            for (int c = 0; c < 100; c++) {
                cells.put("r" + r + ":c" + c, new Cell("text " + r + "/" + c, new Style(r % 2 == 0, false,
                        FontSize.NORMAL), Kind.FIXED));
            }
        }
        long begun = System.nanoTime();
        Written written = writer.write(List.of(Page.one(grid(2, columns, List.of(), rows, cells, List.of()), "x")),
                null);

        assertThat(written.bytes().length).isLessThan(5_000_000);
        assertThat((System.nanoTime() - begun) / 1_000_000).as("milliseconds").isLessThan(20_000);
    }

    // ---- small tables ----

    @Test
    void aSheetNameIsAlwaysAValidOneMadeOnlyByDroppingWhatIsNotAllowed() {
        Set<String> used = new java.util.HashSet<>();

        assertThat(SheetNames.of("Plant [activity]: 2025/26?*", 0, used)).isEqualTo("Plant activity 202526");
        assertThat(SheetNames.of(null, 1, used)).isEqualTo("Sheet2");
        assertThat(SheetNames.of("  '''  ", 2, used)).isEqualTo("Sheet3");
        assertThat(SheetNames.of("plant activity 202526", 3, used)).isEqualTo("plant activity 202526 (2)");
        assertThat(SheetNames.of("x".repeat(80), 4, used)).hasSize(31);
        assertThat(SheetNames.of("History", 5, used)).isEqualTo("History (2)");
        assertThat(SheetNames.of("\u0001\u0002bad\u0000", 6, used)).isEqualTo("bad");
        assertThat(SheetNames.of("'quoted'", 7, used)).isEqualTo("quoted");
        assertThat(SheetNames.of("😀".repeat(20), 8, used).length()).isLessThanOrEqualTo(31);
    }

    @Test
    void aHostileTitleIsAValidWorksheetNameInTheFile() throws IOException {
        // The validator refuses such a title; the writer must still never write an invalid name (defence in depth).
        String hostile = "=1+1 \"quote\" <b>x</b> & more: text/that\\is[long]?*";
        byte[] file = writer.write(List.of(new Page(hostile, sample(), null, null, "", false)), null).bytes();

        try (XSSFWorkbook workbook = open(file)) {
            String name = workbook.getSheetName(0);
            assertThat(name.length()).isLessThanOrEqualTo(31);
            assertThat(name).doesNotContain(":", "\\", "/", "?", "*", "[", "]");
        }
    }

    @Test
    void thePageIsA4ByDefaultAndWhatTheSheetStatesWins() {
        SheetGrid narrow = grid(2, List.of(column("a", "A", 200)), List.of(), List.of(), Map.of(), List.of());

        assertThat(PrintRules.settings(narrow)).isEqualTo(new app.platform.sheets.SheetPages.PrintSettings("A4",
                "portrait", 210, 297, 0.5, 0.5, 0.6, 0.6));
        assertThat(PrintRules.settings(narrow, new SheetDocument.Print("A3", "LANDSCAPE", true, true)))
                .isEqualTo(new app.platform.sheets.SheetPages.PrintSettings("A3", "landscape", 420, 297, 0.5, 0.5, 0.6,
                        0.6));
        assertThat(PrintRules.settings(narrow, new SheetDocument.Print("LETTER", "PORTRAIT", false, false)))
                .isEqualTo(new app.platform.sheets.SheetPages.PrintSettings("LETTER", "portrait", 216, 279, 0.5, 0.5,
                        0.6, 0.6));
    }
}
