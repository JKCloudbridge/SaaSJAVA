package app.platform.sheets.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.sheets.internal.WorkbookWriter.Page;
import app.platform.sheets.internal.WorkbookWriter.Written;
import app.platform.sheets.internal.formula.FormulaLimits;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The file a template is downloaded as, for a sheet in either format, with no database (SH3, ADR-0253): a sheet in
 * format version 1 is written as it always was, a sheet in format version 2 is written with its formulas as formulas
 * and the answer beside each, a text that starts with an equals sign is still text and never a formula, and the
 * information of the file says only that the platform made it and which Revision it is.
 */
class DefaultSheetExportsTest {

    private final WorkbookWriter writer = new WorkbookWriter(ExportProperties.standard());

    private static JsonNode stored(Map<String, Object> document) {
        return GridDocs.JSON.readTree(GridDocs.bytes(document));
    }

    private static RawSheet read(byte[] file) {
        return new WorkbookReader(ImportProperties.standard(), SheetProperties.Limits.standard()).read(file, null);
    }

    @Test
    void aFormulaIsAFormulaInTheFileAndATextThatLooksLikeOneStaysText() {
        Map<String, Object> document = FormulaDocs.named();
        FormulaDocs.withCell(document, "r3:c1", "SUM(@{c1,r1}:@{c1,r2})");
        TwoDocs.cells(document).put("r3:c2", GridDocs.map("value", "=1+1", "kind", "fixed"));

        List<Page> pages = DefaultSheetExports.pagesOf(stored(document), "", FormulaLimits.DEFAULTS);
        Written written = writer.write(pages, null);
        RawSheet back = read(written.bytes());

        assertThat(written.formulas()).isEqualTo(1);
        assertThat(back.formulas.get(4).get(0).text()).isEqualTo("SUM(A3:A4)");
        assertThat(back.typed.get(4).get(0)).isEqualTo(0.0);
        assertThat(back.rows.get(4).get(1).text()).isEqualTo("=1+1");
        assertThat(back.formulas.get(4)).as("the text is not a formula").doesNotContainKey(1);
    }

    @Test
    void anErrorIsStoredBesideTheFormulaAsItsCode() {
        Map<String, Object> document = FormulaDocs.withCell(FormulaDocs.named(), "r3:c1", "1/0");
        Written written = writer.write(DefaultSheetExports.pagesOf(stored(document), "", FormulaLimits.DEFAULTS),
                null);

        RawSheet back = read(written.bytes());
        assertThat(back.formulas.get(4).get(0).text()).isEqualTo("1/0");
        assertThat(back.typed.get(4).get(0)).isInstanceOf(app.platform.sheets.internal.formula.XlError.class);
    }

    @Test
    void aSheetInFormatVersionOneIsOnePageWithTheBandAndTheFooter() {
        Map<String, Object> version1 = GridDocs.minimal();

        List<Page> pages = DefaultSheetExports.pagesOf(stored(version1), "F/MR/16  Rev No.'00'",
                FormulaLimits.DEFAULTS);

        assertThat(pages).hasSize(1);
        assertThat(pages.get(0).tab()).isNull();
        assertThat(pages.get(0).plain()).isFalse();
        assertThat(pages.get(0).footer()).isEqualTo("F/MR/16  Rev No.'00'");
    }

    @Test
    void aSheetInFormatVersionTwoIsOnePagePerSheetWithTheAnswersOfItsFormulas() {
        Map<String, Object> document = FormulaDocs.withCell(FormulaDocs.named(), "r3:c1", "1+2");

        List<Page> pages = DefaultSheetExports.pagesOf(stored(document), "x", FormulaLimits.DEFAULTS);

        assertThat(pages).hasSize(1);
        assertThat(pages.get(0).tab()).isNotNull();
        assertThat(pages.get(0).answers().cells().get("r3:c1").value()).isEqualTo("3");
    }

    @Test
    void theInformationOfTheFileSaysOnlyThePlatformMadeItAndTheRevision() {
        assertThat(DefaultSheetExports.description(1)).isEqualTo("Made by the platform. Revision 01.");
        assertThat(DefaultSheetExports.description(12)).isEqualTo("Made by the platform. Revision 12.");
        assertThat(DefaultSheetExports.description(-4)).isEqualTo("Made by the platform. Revision 00.");
    }
}
