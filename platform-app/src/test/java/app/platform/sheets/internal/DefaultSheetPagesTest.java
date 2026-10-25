package app.platform.sheets.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.platform.sheets.SheetPages.Area;
import app.platform.sheets.SheetPages.Cell;
import app.platform.sheets.SheetPages.Found;
import app.platform.sheets.SheetPages.Page;
import app.platformapi.ApiException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;

/**
 * A page of positioned cells as a sheet document, an Excel file and the cells of a file (FA8, ADR-0202), with no
 * database:
 * the file has no label band and no footer, a text that could be taken for a formula is written as text, the reader of
 * the
 * spreadsheet upload reads the file back to the same cells and merges, and a file that is not a workbook is refused in
 * the
 * upload's own words.
 */
class DefaultSheetPagesTest {

    private final DefaultSheetPages pages = new DefaultSheetPages(
            SpreadsheetImporterTest.importer(ImportProperties.standard()));

    private static Page page() {
        return new Page(List.of(300, 200), List.of(40, 30, 60),
                List.of(new Cell(1, 1, "Title", true, true, true), new Cell(2, 1, "Left", true, false, false),
                        new Cell(2, 2, "two\nlines", false, false, false), new Cell(3, 1, "=1+1", false, false, false),
                        new Cell(3, 2, "@SUM(A1)", false, false, false)),
                List.of(new Area(1, 1, 1, 2)));
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

    @Test
    void theFileHoldsTheCellsFromTheFirstRowWithNoBandOfLabelsAndNoFooter() throws IOException {
        byte[] file = pages.xlsx(page());
        String sheet = part(file, "xl/worksheets/sheet1.xml");

        assertThat(sheet).contains("<row r=\"1\"").contains("<row r=\"3\"").doesNotContain("<row r=\"4\"");
        assertThat(sheet).contains("<dimension ref=\"A1:B3\"/>").contains("<mergeCell ref=\"A1:B1\"/>");
        assertThat(part(file, "xl/workbook.xml")).as("no repeated title rows for a page")
                .doesNotContain("Print_Titles");
    }

    @Test
    void aTextThatCouldBeAFormulaIsWrittenAsTextAndNeverAsAFormula() throws IOException {
        byte[] file = pages.xlsx(page());
        String sheet = part(file, "xl/worksheets/sheet1.xml");

        assertThat(sheet).doesNotContain("<f>").doesNotContain("<f ");
        assertThat(sheet).contains(">=1+1<").contains(">@SUM(A1)<");
        assertThat(part(file, "xl/styles.xml")).contains("quotePrefix=\"1\"");
    }

    @Test
    void theReaderOfTheUploadReadsTheFileBackToTheSameCells() {
        List<Found> back = pages.read(pages.xlsx(page()));

        assertThat(back).extracting(Found::row, Found::column, Found::text).contains(
                org.assertj.core.groups.Tuple.tuple(1, 1, "Title"), org.assertj.core.groups.Tuple.tuple(2, 1, "Left"),
                org.assertj.core.groups.Tuple.tuple(2, 2, "two\nlines"),
                org.assertj.core.groups.Tuple.tuple(3, 1, "=1+1"),
                org.assertj.core.groups.Tuple.tuple(3, 2, "@SUM(A1)"));
    }

    @Test
    void theGridIsTheSheetDocumentTheScreenDraws() {
        Map<String, Object> grid = pages.grid(page());

        assertThat(grid).containsEntry("dimension", 2).containsEntry("rowsMode", "FIXED");
        assertThat((List<?>) grid.get("columns")).hasSize(2);
        assertThat((List<?>) grid.get("rows")).hasSize(3);
        assertThat(((Map<?, ?>) grid.get("cells")).keySet().stream().map(Object::toString).toList())
                .containsExactlyInAnyOrder("r1:c1", "r2:c1", "r2:c2", "r3:c1", "r3:c2");
        assertThat((List<?>) grid.get("merges")).hasSize(1);
        assertThat(grid.toString()).doesNotContain("redacted");
    }

    @Test
    void aCellOutsideThePageIsAProgrammingErrorAndNotAFile() {
        Page bad = new Page(List.of(100), List.of(30), List.of(new Cell(2, 1, "x", false, false, false)), List.of());

        assertThatThrownBy(() -> pages.xlsx(bad)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> pages.grid(new Page(List.of(), List.of(), List.of(), List.of())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aFileThatIsNotAWorkbookIsRefusedInTheUploadsOwnWords() {
        byte[] notAWorkbook = "plain text, not a zip".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> pages.read(notAWorkbook)).isInstanceOf(ApiException.class)
                .hasMessageNotContaining("plain text");
        assertThatThrownBy(() -> pages.read(new byte[0])).isInstanceOf(ApiException.class);
    }

    @Test
    void theFooterRowOfADownloadedSheetIsLeftOutWhenAFileIsReadBackAsASheet() {
        RawSheet raw = new RawSheet();
        raw.put(1, 1, new RawSheet.Cell("Finding", false, false, 11));
        raw.put(2, 1, new RawSheet.Cell("Door", false, false, 11));
        raw.put(3, 2, new RawSheet.Cell("F/MR/16  Rev No.'01'", false, false, 11));

        raw.dropFooter();

        assertThat(raw.rows.keySet()).containsExactly(1, 2);
        assertThat(raw.notes.of(ImportNote.FOOTER_ROW)).isEqualTo(1);
    }

    @Test
    void aLastRowThatIsNotAFooterIsKept() {
        RawSheet raw = new RawSheet();
        raw.put(1, 1, new RawSheet.Cell("Finding", false, false, 11));
        raw.put(2, 1, new RawSheet.Cell("Rev No.'01' is mentioned here", false, false, 11));
        raw.put(2, 2, new RawSheet.Cell("and here", false, false, 11));
        RawSheet single = new RawSheet();
        single.put(1, 1, new RawSheet.Cell("The Rev No.'x' column", false, false, 11));

        raw.dropFooter();
        single.dropFooter();

        assertThat(raw.rows.keySet()).as("two cells in the last row is a row of the sheet").containsExactly(1, 2);
        assertThat(single.rows.keySet()).containsExactly(1);
    }
}
