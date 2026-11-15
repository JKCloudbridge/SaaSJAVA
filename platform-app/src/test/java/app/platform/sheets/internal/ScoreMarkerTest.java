package app.platform.sheets.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.sheets.FillLifecycle.Score;
import app.platform.sheets.internal.SheetDocument.Tab;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The score marker of a sheet (MC2B, ADR-0261) and what is read from the cell it names, with no database: the validator
 * accepts a marker on a formula cell and refuses every other; the canonical text keeps it; a sheet with none is as it
 * was; the answer of the cell becomes a score in percent points, a fraction shown as a percent is multiplied by a
 * hundred, and anything that is not a number from 0 to 100 is never a score.
 */
class ScoreMarkerTest {

    /** A named-rows sheet whose cell r3:c1 is a formula; {@code score} names the marked cell. */
    private static Map<String, Object> document(String score) {
        Map<String, Object> document = FormulaDocs.withCell(FormulaDocs.named(), "r3:c1", "@{c1,r1}/@{c1,r2}*100");
        if (score != null) {
            TwoDocs.sheet(document).put("score", score);
        }
        return document;
    }

    @Test
    void aMarkOnAFormulaCellIsAcceptedAndKeptInTheOneCanonicalForm() {
        Tab tab = FormulaDocs.accepted(document("r3:c1"));
        assertThat(tab.score()).isEqualTo("r3:c1");
        SheetGridValidator.Result result = FormulaDocs.VALIDATOR.check(GridDocs.bytes(document("r3:c1")));
        assertThat(result.checked().canonical()).contains("\"score\":\"r3:c1\"");
    }

    @Test
    void aSheetWithNoMarkIsExactlyAsItWas() {
        Tab tab = FormulaDocs.accepted(document(null));
        assertThat(tab.score()).isNull();
        assertThat(FormulaDocs.VALIDATOR.check(GridDocs.bytes(document(null))).checked().canonical())
                .doesNotContain("score");
    }

    @Test
    void aMarkOnACellThatHoldsNoFormulaIsRefusedWithWordsThatNameNoValue() {
        assertThat(FormulaDocs.refusedWith(document("r1:c1"))).isEqualTo("score_cell");
        assertThat(FormulaDocs.refusedWith(document("r2:c2"))).isEqualTo("score_cell");
        assertThat(FormulaDocs.refusedWith(document("nowhere:c1"))).isEqualTo("score_cell");
    }

    // ---- the answer of the marked cell ----

    private static FillStructure marked(String format) {
        Map<String, Object> document = document("r3:c1");
        if (format != null) {
            TwoDocs.cells(document).put("r3:c1", GridDocs.map("formula", "@{c1,r1}/@{c1,r2}",
                    "look", GridDocs.map("numberFormat", format)));
        }
        Tab tab = FormulaDocs.accepted(document);
        return new FillStructure(tab.body(), tab);
    }

    private static SheetCalculator.Outcome answer(SheetCalculator.Kind kind, String value) {
        return new SheetCalculator.Outcome(Map.of("r3:c1", new SheetCalculator.Answer(kind, value)), Map.of());
    }

    @Test
    void aNumberIsTheScoreInPercentPointsWithTwoDecimalsRoundedHalfUp() {
        Score score = ScoreReading.of(marked(null), answer(SheetCalculator.Kind.NUMBER, "84.995"));
        assertThat(score.state()).isEqualTo(Score.State.CALCULATED);
        assertThat(score.percent()).isEqualByComparingTo("85.00");
        assertThat(ScoreReading.of(marked(null), answer(SheetCalculator.Kind.NUMBER, "85")).percent())
                .isEqualByComparingTo("85.00");
        assertThat(ScoreReading.of(marked(null), answer(SheetCalculator.Kind.NUMBER, "84.994")).percent())
                .isEqualByComparingTo("84.99");
    }

    @Test
    void aCellShownAsAPercentHoldsAFractionAndIsMultipliedByAHundred() {
        assertThat(ScoreReading.of(marked("PERCENT"), answer(SheetCalculator.Kind.NUMBER, "0.9")).percent())
                .isEqualByComparingTo("90.00");
        assertThat(ScoreReading.of(marked("PERCENT_TWO_DECIMALS"), answer(SheetCalculator.Kind.NUMBER, "0.8467"))
                .percent()).isEqualByComparingTo("84.67");
    }

    @Test
    void anErrorTextAYesOrNoNoAnswerAndANumberOutsideZeroToAHundredAreNeverAScore() {
        for (SheetCalculator.Kind kind : new SheetCalculator.Kind[] {SheetCalculator.Kind.ERROR,
                SheetCalculator.Kind.TEXT, SheetCalculator.Kind.BOOLEAN}) {
            assertThat(ScoreReading.of(marked(null), answer(kind, "#DIV/0!")).state())
                    .isEqualTo(Score.State.NOT_CALCULATED);
        }
        assertThat(ScoreReading.of(marked(null), answer(SheetCalculator.Kind.NUMBER, "100.004")).percent())
                .isEqualByComparingTo("100.00");
        assertThat(ScoreReading.of(marked(null), answer(SheetCalculator.Kind.NUMBER, "100.5")).state())
                .isEqualTo(Score.State.NOT_CALCULATED);
        assertThat(ScoreReading.of(marked(null), answer(SheetCalculator.Kind.NUMBER, "-0.01")).state())
                .isEqualTo(Score.State.NOT_CALCULATED);
        assertThat(ScoreReading.of(marked(null), null).state()).isEqualTo(Score.State.NOT_CALCULATED);
    }

    @Test
    void aScorePrintsItsStateAndNeverItsValue() {
        assertThat(ScoreReading.of(marked(null), answer(SheetCalculator.Kind.NUMBER, "77.77")).toString())
                .doesNotContain("77");
    }
}
