package app.platform.sheets.internal;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.StringLength;
import org.junit.jupiter.api.Test;

/**
 * The Excel writer, with no database (FA7, ADR-0201): a file the reader of the spreadsheet upload reads back to the
 * same cells, merges, widths and heights; a text that a spreadsheet program could take for a formula is written as
 * text;
 * the footer row is there with or without a text; the input cells of a template are empty; the page is landscape for a
 * wide sheet. Whatever text is in a cell, the file stays a workbook the reader accepts and holds no formula.
 */
class XlsxWriterTest {

    private static final SheetProperties.Limits LIMITS = SheetProperties.Limits.standard();

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

    private static byte[] write(SheetGrid grid, String footer) {
        return new XlsxWriter().write(grid, footer);
    }

    private static RawSheet read(byte[] file) {
        return new XlsxReader(ImportProperties.standard(), LIMITS).read(file);
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

    private static String text(RawSheet sheet, int row, int column) {
        RawSheet.Cell cell = sheet.rows.getOrDefault(row, new java.util.TreeMap<>()).get(column);
        return cell == null ? null : cell.text();
    }

    // ---- the file the reader reads back ----

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
        assertThat(part(file, "xl/styles.xml")).as("every cell is bordered").contains("<border><left style=\"thin\">");
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

        assertThat(part(write(grid, ""), "xl/worksheets/sheet1.xml"))
                .contains("<dimension ref=\"A1:A13\"/>");
    }

    // ---- what a spreadsheet program must never run ----

    @Test
    void aTextThatLooksLikeAFormulaIsWrittenAsTextWithTheQuotePrefixAndNoFormulaExists() throws IOException {
        Map<String, Cell> cells = new LinkedHashMap<>();
        List<String> hostile = List.of("=1+1", "+cmd|' /C calc'!A0", "-2+3", "@SUM(A1:A9)", "=HYPERLINK(\"http://x\")");
        List<Line> rows = new java.util.ArrayList<>();
        for (int i = 0; i < hostile.size(); i++) {
            rows.add(new Line("r" + i, null, null));
            cells.put("r" + i + ":a", new Cell(hostile.get(i), null, Kind.FIXED));
        }
        byte[] file = write(grid(2, List.of(column("a", "Text", null)), List.of(), rows, cells, List.of()), "");

        String sheet = part(file, "xl/worksheets/sheet1.xml");
        assertThat(sheet).doesNotContain("<f>").doesNotContain("<f ").doesNotContain("<v>");
        assertThat(sheet.split("t=\"inlineStr\"", -1).length - 1).isEqualTo(hostile.size() + 1);
        assertThat(part(file, "xl/styles.xml")).contains("quotePrefix=\"1\"");
        RawSheet back = read(file);
        for (int i = 0; i < hostile.size(); i++) {
            assertThat(text(back, i + 1, 0)).as("the text comes back as it was typed").isEqualTo(hostile.get(i));
        }
    }

    @Test
    void markupAndControlCharactersInATextAreEscapedOrDroppedAndTheWorkbookStaysValid() throws IOException {
        Map<String, Cell> cells = Map.of("r1:a", new Cell("a < b & \"c\" > d\u0001e", null, Kind.FIXED));
        byte[] file = write(grid(2, List.of(column("a", "T</t>", null)), List.of(), List.of(new Line("r1", null, null)),
                cells, List.of()), "");

        String sheet = part(file, "xl/worksheets/sheet1.xml");
        assertThat(sheet).contains("a &lt; b &amp; &quot;c&quot; &gt; de").contains("T&lt;/t&gt;");
        assertThat(sheet).doesNotContain("\u0001");
        assertThat(text(read(file), 1, 0)).isEqualTo("a < b & \"c\" > de");
    }

    @Test
    void theWorkbookHoldsOnlyTheSixPartsAWorkbookNeedsAndNoMacroLinkOrImage() throws IOException {
        List<String> names = new java.util.ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(write(sample(), "x")))) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                names.add(entry.getName());
            }
        }
        assertThat(names).containsExactlyInAnyOrder("[Content_Types].xml", "_rels/.rels", "xl/workbook.xml",
                "xl/_rels/workbook.xml.rels", "xl/styles.xml", "xl/worksheets/sheet1.xml");
    }

    @Property(tries = 60)
    void whateverTextIsInACellTheFileIsAWorkbookTheReaderAcceptsAndHoldsNoFormula(
            @ForAll @StringLength(max = 60) String typed) throws IOException {
        Map<String, Cell> cells = Map.of("r1:a", new Cell(typed.isEmpty() ? null : typed, null, Kind.FIXED));
        byte[] file = write(grid(2, List.of(column("a", "Label", null)), List.of(), List.of(new Line("r1", null, null)),
                cells, List.of()), typed);

        assertThat(part(file, "xl/worksheets/sheet1.xml")).doesNotContain("<f>").doesNotContain("<f ");
        read(file);
    }

    // ---- the page ----

    @Test
    void aWideSheetIsPrintedLandscapeAndANarrowOneIsNot() throws IOException {
        SheetGrid wide = grid(2, List.of(column("a", "A", 400), column("b", "B", 400)), List.of(), List.of(),
                Map.of(), List.of());
        SheetGrid narrow = grid(2, List.of(column("a", "A", 200)), List.of(), List.of(), Map.of(), List.of());

        assertThat(XlsxWriter.landscape(wide)).isTrue();
        assertThat(XlsxWriter.landscape(narrow)).isFalse();
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
    void aReferenceIsWrittenAsALetterAndARow() {
        assertThat(XlsxWriter.ref(1, 1)).isEqualTo("A1");
        assertThat(XlsxWriter.ref(26, 9)).isEqualTo("Z9");
        assertThat(XlsxWriter.ref(27, 10)).isEqualTo("AA10");
        assertThat(XlsxWriter.looksLikeFormula("=a")).isTrue();
        assertThat(XlsxWriter.looksLikeFormula("a=")).isFalse();
        assertThat(XlsxWriter.looksLikeFormula("")).isFalse();
    }
}
