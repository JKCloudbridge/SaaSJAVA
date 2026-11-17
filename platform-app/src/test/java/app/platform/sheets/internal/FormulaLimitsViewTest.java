package app.platform.sheets.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.sheets.internal.formula.FormulaFunctions;
import app.platformapi.SheetFormulaFunctionView;
import app.platformapi.SheetFormulaLimitsView;
import org.junit.jupiter.api.Test;

/**
 * What the screens are told about the formulas of a sheet (SH2, SH5.1): the list of versions and the fill-in read the
 * same answer, made from the server's own settings and its own table of functions, so a browser that previews answers
 * while a person types holds no list or number of its own.
 */
class FormulaLimitsViewTest {

    @Test
    void theViewCarriesTheBoundsAndTheTableOfTheServer() {
        FormulaProperties properties = new FormulaProperties(3000, 20, 800, 50_000, 60_000, 1500, 8, 300);

        SheetFormulaLimitsView view = properties.view();

        assertThat(view.maxChars()).isEqualTo(3000);
        assertThat(view.maxDepth()).isEqualTo(20);
        assertThat(view.maxNodes()).isEqualTo(800);
        assertThat(view.maxSteps()).isEqualTo(50_000);
        assertThat(view.maxTextChars()).isEqualTo(60_000);
        assertThat(view.maxChainDepth()).isEqualTo(8);
        assertThat(view.maxFormulas()).isEqualTo(300);
        assertThat(view.functions()).extracting(SheetFormulaFunctionView::name)
                .containsExactlyElementsOf(FormulaFunctions.all().stream().map(function -> function.name()).toList());
        assertThat(view.textFormats()).containsExactlyElementsOf(FormulaFunctions.TEXT_FORMAT_LIST);
    }

    @Test
    void theTableNamesSumAndTheLeastAndMostArgumentsOfEachFunction() {
        SheetFormulaLimitsView view = new FormulaProperties(4000, 32, 1000, 100_000, 100_000, 2000, 10, 500).view();

        SheetFormulaFunctionView sum = view.functions().stream()
                .filter(function -> function.name().equals("SUM")).findFirst().orElseThrow();

        assertThat(sum.least()).isGreaterThanOrEqualTo(1);
        assertThat(sum.most()).isGreaterThanOrEqualTo(sum.least());
    }
}
