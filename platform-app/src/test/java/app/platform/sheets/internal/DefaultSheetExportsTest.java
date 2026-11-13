package app.platform.sheets.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.sheets.internal.SheetDocument.Tab;
import app.platform.sheets.internal.formula.FormulaLimits;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The file a template is downloaded as, for a sheet with formulas (SH2, ADR-0252), with no database: until the release
 * that writes formulas into a file (SH3) the answer the server calculates is written as text, a text that starts with
 * an equals sign is still text and never a formula, and the file says so in its information.
 */
class DefaultSheetExportsTest {

    private static final SheetProperties.Limits LIMITS = SheetProperties.Limits.standard();

    private static List<String> textsOf(byte[] file) {
        RawSheet raw = new WorkbookReader(ImportProperties.standard(), LIMITS).read(file, null);
        assertThat(raw.formulas).as("the file holds no formula").isEmpty();
        List<String> texts = new ArrayList<>();
        raw.rows.values().forEach(row -> row.values().forEach(cell -> texts.add(cell.text())));
        return texts;
    }

    @Test
    void anAnswerIsWrittenAsTextAndNeverAsAFormula() {
        Map<String, Object> document = FormulaDocs.withCell(FormulaDocs.named(), "r3:c1", "SUM(@{c1,r1}:@{c1,r2})");
        FormulaDocs.withCell(document, "r3:c2", "\"=1+1\"");
        Tab tab = FormulaDocs.accepted(document);

        SheetGrid grid = DefaultSheetExports.withAnswers(tab.body(), tab, FormulaLimits.DEFAULTS);
        byte[] file = new XlsxWriter().write(grid, "", false, DefaultSheetExports.LOOK_NOT_YET
                + DefaultSheetExports.FORMULAS_AS_ANSWERS);

        assertThat(textsOf(file)).contains("0", "=1+1");
    }

    @Test
    void anErrorIsWrittenAsItsCode() {
        Map<String, Object> document = FormulaDocs.withCell(FormulaDocs.named(), "r3:c1", "1/0");
        Tab tab = FormulaDocs.accepted(document);

        SheetGrid grid = DefaultSheetExports.withAnswers(tab.body(), tab, FormulaLimits.DEFAULTS);

        assertThat(grid.cells().get("r3:c1").value()).isEqualTo("#DIV/0!");
        assertThat(textsOf(new XlsxWriter().write(grid, "", false, null))).contains("#DIV/0!");
    }

    @Test
    void theInformationOfTheFileSaysFormulasAreWrittenAsAnswers() {
        assertThat(DefaultSheetExports.FORMULAS_AS_ANSWERS).contains("calculated answers").contains("not as formulas");
    }
}
