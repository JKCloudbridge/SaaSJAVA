package app.platform.sheets.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.sheets.internal.SheetCalculator.Answer;
import app.platform.sheets.internal.SheetCalculator.Inputs;
import app.platform.sheets.internal.SheetCalculator.Kind;
import app.platform.sheets.internal.SheetCalculator.Outcome;
import app.platform.sheets.internal.SheetDocument.Tab;
import app.platform.sheets.internal.formula.FormulaLimits;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The pure call that gives every formula of a pinned sheet its answer from the values of the input cells (SH2,
 * ADR-0252), with no database: a total over named rows, a calculated column drawn at each row the filler added, the
 * whole body of a column that grows with the rows, answers that depend on answers, an error shown as its code, and the
 * bounds. This is the call SH5 uses for a fill-in.
 */
class SheetCalculatorTest {

    private static final FormulaLimits LIMITS = FormulaLimits.DEFAULTS;

    private static Answer number(String digits) {
        return new Answer(Kind.NUMBER, digits);
    }

    @Test
    void aTotalOverNamedRowsIsCalculatedFromTheValuesOfTheInputCells() {
        Tab tab = FormulaDocs.accepted(FormulaDocs.withCell(FormulaDocs.named(), "r3:c1", "SUM(@{c1,r1}:@{c1,r2})"));

        Outcome outcome = SheetCalculator.calculate(tab, new Inputs(Map.of("r1:c1", 2.0, "r2:c1", 3.5), List.of()),
                LIMITS);

        assertThat(outcome.cells()).containsEntry("r3:c1", number("5.5"));
    }

    @Test
    void anEmptyInputCellCountsAsNothingAndATotalOfNothingIsZero() {
        Tab tab = FormulaDocs.accepted(FormulaDocs.withCell(FormulaDocs.named(), "r3:c1", "SUM(@{c1,r1}:@{c1,r2})"));

        assertThat(SheetCalculator.calculate(tab, Inputs.none(), LIMITS).cells()).containsEntry("r3:c1", number("0"));
    }

    @Test
    void aCalculatedColumnIsDrawnAtEveryRowTheFillerAdded() {
        Tab tab = FormulaDocs.accepted(FormulaDocs.withColumn(FormulaDocs.repeating(), "c2",
                "IF(@{c1}=\"\",\"\",@{c1}*2)"));
        Inputs rows = new Inputs(Map.of(), List.of(Map.of("c1", 4.0), Map.of(), Map.of("c1", 0.5)));

        Outcome outcome = SheetCalculator.calculate(tab, rows, LIMITS);

        assertThat(outcome.columns().get("c2")).containsExactly(number("8"), new Answer(Kind.TEXT, ""), number("1"));
    }

    @Test
    void theWholeBodyOfAColumnGrowsWithTheRowsTheFillerAdds() {
        Tab tab = FormulaDocs.accepted(FormulaDocs.withCell(FormulaDocs.repeating(), "h1:c2", "AVERAGE(@{c1,*})"));

        Outcome three = SheetCalculator.calculate(tab, new Inputs(Map.of(),
                List.of(Map.of("c1", 2.0), Map.of("c1", 4.0), Map.of("c1", 9.0))), LIMITS);
        Outcome five = SheetCalculator.calculate(tab, new Inputs(Map.of(),
                List.of(Map.of("c1", 2.0), Map.of("c1", 4.0), Map.of("c1", 9.0), Map.of("c1", 10.0),
                        Map.of("c1", 15.0))), LIMITS);

        assertThat(three.cells()).containsEntry("h1:c2", number("5"));
        assertThat(five.cells()).containsEntry("h1:c2", number("8"));
    }

    @Test
    void anAnswerMayDependOnTheAnswersOfACalculatedColumn() {
        Map<String, Object> document = FormulaDocs.withColumn(FormulaDocs.repeating(), "c2", "@{c1}*10");
        FormulaDocs.withCell(document, "h1:c1", "SUM(@{c2,*})");
        Tab tab = FormulaDocs.accepted(document);

        Outcome outcome = SheetCalculator.calculate(tab, new Inputs(Map.of(),
                List.of(Map.of("c1", 3.0), Map.of("c1", 4.0))), LIMITS);

        assertThat(outcome.columns().get("c2")).containsExactly(number("30"), number("40"));
        assertThat(outcome.cells()).containsEntry("h1:c1", number("70"));
    }

    @Test
    void aChainOfCellsIsCalculatedInTheOrderItNeeds() {
        Map<String, Object> document = FormulaDocs.withCell(FormulaDocs.named(), "r3:c1", "@{c1,r2}+1");
        FormulaDocs.withCell(document, "r2:c1", "@{c1,r1}+1");
        Tab tab = FormulaDocs.accepted(document);

        Outcome outcome = SheetCalculator.calculate(tab, new Inputs(Map.of("r1:c1", 1.0), List.of()), LIMITS);

        assertThat(outcome.cells()).containsEntry("r2:c1", number("2")).containsEntry("r3:c1", number("3"));
    }

    @Test
    void anErrorIsShownAsItsCodeAndNeverHidden() {
        Tab tab = FormulaDocs.accepted(FormulaDocs.withCell(FormulaDocs.named(), "r3:c1", "@{c1,r1}/@{c2,r1}"));

        Outcome outcome = SheetCalculator.calculate(tab, new Inputs(Map.of("r1:c1", 1.0, "r1:c2", 0.0), List.of()),
                LIMITS);

        assertThat(outcome.cells()).containsEntry("r3:c1", new Answer(Kind.ERROR, "#DIV/0!"));
    }

    @Test
    void aFixedCellOfTheSheetIsItsText() {
        Map<String, Object> document = FormulaDocs.withCell(FormulaDocs.named(), "r3:c1", "LEN(@{c1,r1})");
        TwoDocs.cells(document).put("r1:c1", GridDocs.map("value", "hello", "kind", "fixed"));
        Tab tab = FormulaDocs.accepted(document);

        assertThat(SheetCalculator.calculate(tab, Inputs.none(), LIMITS).cells()).containsEntry("r3:c1", number("5"));
    }

    @Test
    void aTextAnswerThatLooksLikeAFormulaOrMarkupIsJustText() {
        Map<String, Object> document = FormulaDocs.withCell(FormulaDocs.named(), "r3:c1", "@{c1,r1}");
        Tab tab = FormulaDocs.accepted(document);

        Outcome outcome = SheetCalculator.calculate(tab, new Inputs(Map.of("r1:c1", "=1+1<b>x</b>"), List.of()),
                LIMITS);

        assertThat(outcome.cells().get("r3:c1")).isEqualTo(new Answer(Kind.TEXT, "=1+1<b>x</b>"));
    }

    @Test
    void aStepBoundGivesTheLimitErrorForThatFormulaAlone() {
        Map<String, Object> document = FormulaDocs.withCell(FormulaDocs.named(), "r3:c1", "SUM(@{c1,r1}:@{c2,r2})");
        FormulaDocs.withCell(document, "r3:c2", "1+1");
        Tab tab = FormulaDocs.accepted(document);

        Outcome outcome = SheetCalculator.calculate(tab, Inputs.none(), new FormulaLimits(4000, 32, 1000, 5, 100, 2000,
                10, 500));

        assertThat(outcome.cells().get("r3:c1")).isEqualTo(new Answer(Kind.ERROR, "#LIMIT!"));
        assertThat(outcome.cells().get("r3:c2")).isEqualTo(number("2"));
    }

    @Test
    void theCalculationIsPureAndRepeatable() {
        Tab tab = FormulaDocs.accepted(FormulaDocs.withCell(FormulaDocs.named(), "r3:c1", "SUM(@{c1,r1}:@{c1,r2})"));
        Inputs inputs = new Inputs(Map.of("r1:c1", 1.5, "r2:c1", 2.5), List.of());

        assertThat(SheetCalculator.calculate(tab, inputs, LIMITS).cells())
                .isEqualTo(SheetCalculator.calculate(tab, inputs, LIMITS).cells());
    }

    @Test
    void theAnswerTypesNeverPrintTheirContent() {
        assertThat(number("5").toString()).doesNotContain("5");
        assertThat(SheetCalculator.calculate(FormulaDocs.accepted(FormulaDocs.named()), Inputs.none(), LIMITS)
                .toString()).isEqualTo("Outcome[redacted]");
    }
}
