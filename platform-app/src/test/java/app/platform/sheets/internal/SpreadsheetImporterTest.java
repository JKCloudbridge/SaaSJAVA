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
        return new SpreadsheetImporter(settings,
                new SheetProperties(LIMITS, 4096, new SheetProperties.FormatTwo(false)),
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
        assertThat(WorkbookReader.date(45000, false)).isEqualTo("2023-03-15");
        assertThat(WorkbookReader.date(45000.5, false)).isEqualTo("2023-03-15 12:00");
        assertThat(WorkbookReader.date(0.25, false)).isEqualTo("06:00");
        assertThat(WorkbookReader.date(1, false)).isEqualTo("1900-01-01");
        assertThat(WorkbookReader.date(61, false)).isEqualTo("1900-03-01");
        assertThat(WorkbookReader.date(0, true)).isEqualTo("1904-01-01");
        assertThat(WorkbookReader.date(-1, false)).isNull();
        assertThat(NumberFormats.isDateFormat("yyyy-mm-dd")).isTrue();
        assertThat(NumberFormats.isDateFormat("0.00 \"days\"")).isFalse();
        assertThat(NumberFormats.isDateFormat("#,##0")).isFalse();
    }

    @Test
    void aCellReferenceIsParsedAndAnythingElseIsNot() {
        assertThat(WorkbookReader.reference("A1")).containsExactly(0, 0);
        assertThat(WorkbookReader.reference("AA10")).containsExactly(9, 26);
        assertThat(WorkbookReader.reference("XFD1048576")).containsExactly(1_048_575, 16_383);
        assertThat(WorkbookReader.reference("XFE1")).isNull();
        assertThat(WorkbookReader.reference("A0")).isNull();
        assertThat(WorkbookReader.reference("A1048577")).isNull();
        assertThat(WorkbookReader.reference("1A")).isNull();
        assertThat(WorkbookReader.reference("")).isNull();
        assertThat(SheetImportBuilder.letters(0)).isEqualTo("A");
        assertThat(SheetImportBuilder.letters(25)).isEqualTo("Z");
        assertThat(SheetImportBuilder.letters(26)).isEqualTo("AA");
    }

    // ---- cells with a border and no text, and the title row (FA14, ADR-0250) ----

    private static Map<String, Integer> counts(Imported imported) {
        Map<String, Integer> counts = new java.util.HashMap<>();
        imported.report().notes().forEach(entry -> counts.put(entry.note().code(), entry.count()));
        return counts;
    }

    private static Imported readWith(byte[] file, Boolean firstRowLabels) {
        return importer(ImportProperties.standard()).read(file, Format.XLSX,
                new SheetImportBuilder.Options(firstRowLabels, 2, RowsMode.FIXED));
    }

    @Test
    void aBlankCellWithABorderExtendsTheGridAndOneWithoutDoesNot() {
        byte[] file = new XlsxFile().row(1, XlsxFile.text("A1", "h"), XlsxFile.text("B1", "i"))
                .row(2, XlsxFile.text("A2", "x"), XlsxFile.boxed("B2"), XlsxFile.boxed("C2"), XlsxFile.blank("D2"))
                .row(3, XlsxFile.boxed("A3"))
                .row(5, "<c r=\"E5\" s=\"0\"/>", XlsxFile.noBorder("F5")).bytes();

        Imported imported = read(file, Format.XLSX);
        SheetGrid grid = imported.checked().grid();

        assertThat(grid.columns()).as("A to C: D and beyond have no border").hasSize(3);
        assertThat(grid.rows()).as("rows 2 and 3: row 5 has no border").hasSize(2);
        assertThat(grid.cells()).as("only the text is a value").hasSize(1);
        assertThat(cellAt(grid, 0, 1)).as("a bordered blank cell is for the filler").isNull();
        assertThat(counts(imported)).containsEntry("bordered_blanks", 3);
        assertThat(imported.report().notes()).extracting(entry -> entry.note().words(entry.count()))
                .contains("3 blank cells with a border were kept as cells to be filled in (a blank cell with no "
                        + "border is not part of the table).");
    }

    @Test
    void aBorderOnOneSideIsEnoughAndASideCalledNoneIsNoBorder() {
        byte[] oneSide = new XlsxFile().row(1, XlsxFile.text("A1", "h"), XlsxFile.underlined("C1")).bytes();
        byte[] none = new XlsxFile().row(1, XlsxFile.text("A1", "h"), XlsxFile.noBorder("C1"),
                XlsxFile.strayBorder("D1")).bytes();

        assertThat(read(oneSide, Format.XLSX).checked().grid().columns()).hasSize(3);
        assertThat(read(none, Format.XLSX).checked().grid().columns()).hasSize(1);
    }

    @Test
    void aBlankBorderedRowInTheMiddleAndAtTheEndIsPartOfTheTable() {
        byte[] file = new XlsxFile().row(1, XlsxFile.text("A1", "h"))
                .row(2, XlsxFile.boxed("A2")).row(3, XlsxFile.text("A3", "x")).row(5, XlsxFile.boxed("A5")).bytes();

        SheetGrid grid = read(file, Format.XLSX).checked().grid();

        assertThat(grid.rows()).as("rows 2 to 5: a row with nothing in it between is a row").hasSize(4);
        assertThat(grid.cells()).hasSize(1);
    }

    @Test
    void aMergedAreaOverBorderedBlanksKeepsItsWholeArea() {
        byte[] file = new XlsxFile().row(1, XlsxFile.text("A1", "a"), XlsxFile.text("B1", "b"),
                XlsxFile.text("C1", "c")).row(2, XlsxFile.boxed("A2")).merge("A2:C6").bytes();

        Imported imported = read(file, Format.XLSX);
        SheetGrid grid = imported.checked().grid();

        assertThat(grid.rows()).as("rows 2 to 6 come from the merge, written only at its first cell").hasSize(5);
        assertThat(grid.merges()).hasSize(1);
        assertThat(grid.merges().get(0).from()).isEqualTo(grid.rows().get(0).id() + ":" + grid.columns().get(0).id());
        assertThat(grid.merges().get(0).to()).isEqualTo(grid.rows().get(4).id() + ":" + grid.columns().get(2).id());
        assertThat(counts(imported)).as("a merged area is one cell").containsEntry("bordered_blanks", 1);
    }

    @Test
    void aMergedAreaWithNoBorderAtAllDoesNotExtendTheGrid() {
        byte[] file = new XlsxFile().row(1, XlsxFile.text("A1", "a"), XlsxFile.text("B1", "b"))
                .row(2, XlsxFile.text("A2", "x")).merge("A3:D6").bytes();

        Imported imported = read(file, Format.XLSX);

        assertThat(imported.checked().grid().rows()).hasSize(1);
        assertThat(imported.checked().grid().merges()).isEmpty();
        assertThat(counts(imported)).containsEntry("merges_left_out", 1);
    }

    @Test
    void borderedBlankRowsAreAcceptedAtTheLimitAndRefusedAboveItWithTheNumber() {
        XlsxFile atTheLimit = new XlsxFile().row(1, XlsxFile.text("A1", "h"));
        XlsxFile above = new XlsxFile().row(1, XlsxFile.text("A1", "h"));
        for (int row = 2; row <= LIMITS.maxRows() + 2; row++) {
            if (row <= LIMITS.maxRows() + 1) {
                atTheLimit.row(row, XlsxFile.boxed("A" + row));
            }
            above.row(row, XlsxFile.boxed("A" + row));
        }

        assertThat(read(atTheLimit.bytes(), Format.XLSX).checked().grid().rows()).hasSize(LIMITS.maxRows());
        ImportRefusal refusal = refused(above.bytes(), Format.XLSX);
        assertThat(refusal.code()).isEqualTo("too_many_rows");
        assertThat(refusal.words()).contains("501").contains("500");
    }

    @Test
    void aBorderedBlankColumnPastTheLimitIsRefusedAtOnceWithTheNumber() {
        byte[] file = new XlsxFile().row(1, XlsxFile.text("A1", "h"),
                XlsxFile.boxed(SheetImportBuilder.letters(LIMITS.maxColumns()) + "1")).bytes();

        ImportRefusal refusal = refused(file, Format.XLSX);

        assertThat(refusal.code()).isEqualTo("too_many_columns");
        assertThat(refusal.words()).contains("101").contains("100");
    }

    @Test
    void stylesWithNoListOfBordersGiveNoBorders() {
        byte[] file = new XlsxFile().styles(XlsxFile.STYLES_WITHOUT_BORDERS)
                .row(1, XlsxFile.text("A1", "h"), XlsxFile.boxed("C1")).bytes();

        assertThat(read(file, Format.XLSX).checked().grid().columns()).hasSize(1);
    }

    @Test
    void theListOfBordersIsAcceptedAtTheMostAWorkbookCanHaveAndRefusedAboveIt() {
        assertThat(read(withBorders(64_000), Format.XLSX).checked().grid().columns()).hasSize(2);

        ImportRefusal refusal = refused(withBorders(64_001), Format.XLSX);
        assertThat(refusal.code()).isEqualTo("styles");
        assertThat(refusal.kind()).isEqualTo(ImportRefusal.Kind.TOO_LARGE);
    }

    /** Styles with the given number of borders, the last of them a box, and a format that uses the last one. */
    private static byte[] withBorders(int count) {
        String main = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
        String styles = "<styleSheet xmlns=\"" + main + "\"><borders count=\"" + count + "\">"
                + "<border/>".repeat(count - 1) + "<border><left style=\"thin\"/></border></borders>"
                + "<cellXfs count=\"2\"><xf borderId=\"0\"/><xf borderId=\"" + (count - 1) + "\"/></cellXfs>"
                + "</styleSheet>";
        return new XlsxFile().styles(styles)
                .row(1, XlsxFile.text("A1", "h"), "<c r=\"B1\" s=\"1\"/>").bytes();
    }

    @Test
    void aBorderInsideAConditionalFormatIsNotACellBorder() {
        String main = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
        String styles = "<styleSheet xmlns=\"" + main + "\"><cellXfs count=\"2\"><xf borderId=\"0\"/>"
                + "<xf borderId=\"0\"/></cellXfs><dxfs count=\"1\"><dxf><border><left style=\"thin\"/></border>"
                + "</dxf></dxfs></styleSheet>";
        byte[] file = new XlsxFile().styles(styles).row(1, XlsxFile.text("A1", "h"), "<c r=\"C1\" s=\"1\"/>").bytes();

        assertThat(read(file, Format.XLSX).checked().grid().columns()).hasSize(1);
    }

    @Test
    void aMergedTitleAcrossTheWholeWidthIsATitleNotTheNameOfAColumn() {
        byte[] file = new XlsxFile()
                .row(1, XlsxFile.text("A1", "FORM TITLE", XlsxFile.TITLE), XlsxFile.boxed("B1"), XlsxFile.boxed("C1"))
                .row(2, XlsxFile.text("A2", "No."), XlsxFile.text("B2", "Item"), XlsxFile.text("C2", "Answer"))
                .merge("A1:C1").bytes();

        Imported automatic = readWith(file, null);
        SheetGrid grid = automatic.checked().grid();

        assertThat(automatic.report().firstRowIsTitle()).isTrue();
        assertThat(automatic.report().firstRowLabels()).as("the server leaves the box unticked").isFalse();
        assertThat(grid.columns()).extracting(SheetGrid.Column::label).containsExactly("Column 1", "Column 2",
                "Column 3");
        assertThat(grid.rows()).hasSize(2);
        assertThat(cellAt(grid, 0, 0).value()).isEqualTo("FORM TITLE");
        assertThat(cellAt(grid, 0, 0).style().fontSize()).isEqualTo(FontSize.TITLE);
        assertThat(grid.merges()).hasSize(1);
        assertThat(counts(automatic)).containsEntry("title_row", 1);
        assertThat(automatic.report().notes()).extracting(entry -> entry.note().words(entry.count()))
                .contains("1 title row was found (it is kept as the first row of the sheet and does not name a "
                        + "column).");
    }

    @Test
    void thePersonCanStillTickTheBoxForAFileWithATitle() {
        byte[] file = new XlsxFile().row(1, XlsxFile.text("A1", "FORM TITLE"), XlsxFile.boxed("B1"))
                .row(2, XlsxFile.text("A2", "No."), XlsxFile.text("B2", "Item")).merge("A1:B1").bytes();

        Imported ticked = readWith(file, true);

        assertThat(ticked.report().firstRowIsTitle()).as("still said, so the screen can say it").isTrue();
        assertThat(ticked.report().firstRowLabels()).isTrue();
        assertThat(ticked.checked().grid().columns().get(0).label()).isEqualTo("FORM TITLE");
        assertThat(counts(ticked)).doesNotContainKey("title_row");
    }

    @Test
    void aMergedFirstRowThatDoesNotSpanTheWholeWidthIsAnOrdinaryRow() {
        byte[] file = new XlsxFile().row(1, XlsxFile.text("A1", "Group"), XlsxFile.boxed("B1"))
                .row(2, XlsxFile.text("A2", "a"), XlsxFile.text("B2", "b"), XlsxFile.text("C2", "c"))
                .merge("A1:B1").bytes();

        Imported imported = readWith(file, null);

        assertThat(imported.report().firstRowIsTitle()).isFalse();
        assertThat(imported.report().firstRowLabels()).isTrue();
        assertThat(imported.checked().grid().columns().get(0).label()).isEqualTo("Group");
    }

    @Test
    void aFileWithNoTitleKeepsTheDefaultOfTheFirstRowNamingTheColumns() {
        Imported imported = readWith(goodWorkbook(), null);

        assertThat(imported.report().firstRowIsTitle()).isFalse();
        assertThat(imported.report().firstRowLabels()).isTrue();
        assertThat(imported.checked().grid().columns()).extracting(SheetGrid.Column::label)
                .containsExactly("Name", "Qty", "Date", "Note");
    }

    @Test
    void aFirstRowWithTwoTextsOrOneColumnIsNotATitleEvenWhenMerged() {
        byte[] twoTexts = new XlsxFile().row(1, XlsxFile.text("A1", "One"), XlsxFile.text("C1", "Two"))
                .row(2, XlsxFile.text("A2", "a"), XlsxFile.text("B2", "b"), XlsxFile.text("C2", "c"))
                .merge("A1:C1").bytes();
        byte[] oneColumn = new XlsxFile().row(1, XlsxFile.text("A1", "One")).row(2, XlsxFile.text("A2", "a"))
                .merge("A1:A1").bytes();

        assertThat(readWith(twoTexts, null).report().firstRowIsTitle()).isFalse();
        assertThat(readWith(oneColumn, null).report().firstRowIsTitle()).isFalse();
    }

    @Test
    void aTextFileIsNeverATitleAndKeepsItsBehaviour() {
        Imported imported = importer(ImportProperties.standard()).read(csv("Name,Note\nA,b\n"), Format.CSV,
                new SheetImportBuilder.Options(null, 2, RowsMode.FIXED));

        assertThat(imported.report().firstRowIsTitle()).isFalse();
        assertThat(imported.report().firstRowLabels()).isTrue();
        assertThat(imported.checked().grid().columns()).extracting(SheetGrid.Column::label)
                .containsExactly("Name", "Note");
        assertThat(counts(imported)).doesNotContainKey("bordered_blanks");
    }

    // ---- the sample form, compared with what the file shows ----

    @Test
    void theSampleFormComesInAsItLooks() {
        Imported imported = readWith(SampleForms.analysisForm(), null);
        SheetGrid grid = imported.checked().grid();

        assertThat(grid.columns()).as("the table has three columns: the blank cells outside it are not columns")
                .hasSize(SampleForms.COLUMNS);
        assertThat(grid.rows()).as("and fifteen rows, down to the merged answer area").hasSize(SampleForms.ROWS);
        assertThat(grid.cells()).as("only the texts are values").hasSize(SampleForms.TEXT_CELLS);
        assertThat(imported.report().firstRowIsTitle()).isTrue();
        assertThat(imported.report().firstRowLabels()).isFalse();
        assertThat(grid.columns().get(0).label()).as("the title is not the name of column 1").isEqualTo("Column 1");
        assertThat(cellAt(grid, 0, 0).value()).isEqualTo(SampleForms.TITLE);
        assertThat(cellAt(grid, 0, 0).style().bold()).isTrue();
        assertThat(cellAt(grid, 0, 0).style().fontSize()).isEqualTo(FontSize.TITLE);
        assertThat(grid.merges()).as("the title and the answer area").hasSize(2);
        String c0 = grid.columns().get(0).id();
        String c2 = grid.columns().get(2).id();
        assertThat(grid.merges()).extracting(m -> m.from() + ">" + m.to()).containsExactlyInAnyOrder(
                grid.rows().get(0).id() + ":" + c0 + ">" + grid.rows().get(0).id() + ":" + c2,
                grid.rows().get(10).id() + ":" + c0 + ">" + grid.rows().get(14).id() + ":" + c2);
        assertThat(grid.columns()).extracting(SheetGrid.Column::width).containsExactly(47, 159, 425);
        assertThat(grid.rows().get(0).height()).isEqualTo(60);
        assertThat(grid.rows().get(2).height()).isEqualTo(53);
        assertThat(grid.rows().get(4).height()).isEqualTo(40);
        assertThat(grid.rows().get(8).height()).isEqualTo(40);
        assertThat(cellAt(grid, 2, 2)).as("a blank answer cell with a border is for the filler").isNull();
        assertThat(counts(imported)).containsEntry("bordered_blanks", SampleForms.BORDERED_BLANKS)
                .containsEntry("title_row", 1);
    }

    @Test
    void theSampleFormWithoutItsBordersReachesOnlyAsFarAsItsText() {
        Imported imported = readWith(SampleForms.analysisForm(false), null);
        SheetGrid grid = imported.checked().grid();

        assertThat(grid.columns()).hasSize(SampleForms.COLUMNS);
        assertThat(grid.rows()).as("the text ends in row 10").hasSize(10);
        assertThat(counts(imported)).doesNotContainKey("bordered_blanks").containsEntry("merges_left_out", 1);
        assertThat(imported.report().firstRowIsTitle()).isTrue();
    }

    @Test
    void aFileThatIsBorderedAllOverIsRefusedByTheLimitsWhileItIsRead() {
        ImportProperties few = new ImportProperties(5_000_000, 20_000_000, 100_000_000, 100, 100, 4);
        XlsxFile file = new XlsxFile();
        for (int row = 1; row <= 11; row++) {
            String[] cells = new String[11];
            for (int column = 0; column < cells.length; column++) {
                cells[column] = XlsxFile.boxed(SheetImportBuilder.letters(column) + row);
            }
            file.row(row, cells);
        }
        file.row(12, XlsxFile.text("A12", "x"));

        ImportRefusal refusal = refused(few, file.bytes(), Format.XLSX, NAMED_ROWS);

        assertThat(refusal.code()).isEqualTo("cells");
        assertThat(refusal.kind()).isEqualTo(ImportRefusal.Kind.TOO_LARGE);
    }

    @Test
    void theFooterRowOfADownloadedFileIsStillDroppedWhenTheTableIsBordered() {
        byte[] file = new XlsxFile().row(1, XlsxFile.text("A1", "h"), XlsxFile.text("B1", "i"))
                .row(2, XlsxFile.boxed("A2"), XlsxFile.boxed("B2"))
                .row(3, XlsxFile.text("B3", "Rev No.'01'")).bytes();

        Imported imported = read(file, Format.XLSX);

        assertThat(imported.checked().grid().rows()).hasSize(1);
        assertThat(counts(imported)).containsEntry("footer_row", 1);
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
                xml[c] = random.nextInt(5) == 0 ? XlsxFile.boxed(ref)
                        : XlsxFile.text(ref, pieces[random.nextInt(pieces.length)], random.nextInt(8));
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
