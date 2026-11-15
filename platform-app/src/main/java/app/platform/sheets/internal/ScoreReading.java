package app.platform.sheets.internal;

import app.platform.sheets.FillLifecycle.Score;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Turns the answer of the cell a sheet marks as its score into the score of a fill-in (MC2B, ADR-0261). Pure: the
 * answers come in, a score or "not calculated" goes out.
 *
 * <p><strong>Units.</strong> The score is in percent points: 90.00 is ninety percent. A cell whose number format is a
 * percent format holds a fraction (0.9 is shown as 90%), so its answer is multiplied by a hundred; any other cell is
 * taken as the percent points it holds. The result is rounded half up to two decimals.
 *
 * <p><strong>Never a pass by accident.</strong> An error answer, text, a yes or no, no answer at all, and a number
 * outside 0 to 100 are all "not calculated": a score that cannot be relied on never decides that a fill-in needs no
 * Action plan. Nothing here repeats a value: the answer is the organization's.
 */
final class ScoreReading {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private ScoreReading() {
    }

    /**
     * The score of a sheet that marks one.
     *
     * @param structure the pinned sheet, which marks a score cell
     * @param outcome the answers of its formulas, or null when it has none
     */
    static Score of(FillStructure structure, SheetCalculator.Outcome outcome) {
        String key = structure.tab().score();
        SheetCalculator.Answer answer = outcome == null || key == null ? null : outcome.cells().get(key);
        if (answer == null || answer.kind() != SheetCalculator.Kind.NUMBER) {
            return Score.notCalculated();
        }
        BigDecimal value;
        try {
            value = new BigDecimal(answer.value());
        } catch (NumberFormatException e) {
            return Score.notCalculated();
        }
        SheetLook look = structure.tab().looks().get(key);
        String format = look == null ? null : look.numberFormat();
        if ("PERCENT".equals(format) || "PERCENT_TWO_DECIMALS".equals(format)) {
            value = value.multiply(HUNDRED);
        }
        BigDecimal percent = value.setScale(2, RoundingMode.HALF_UP);
        if (percent.signum() < 0 || percent.compareTo(HUNDRED) > 0) {
            return Score.notCalculated();
        }
        return new Score(Score.State.CALCULATED, percent);
    }
}
