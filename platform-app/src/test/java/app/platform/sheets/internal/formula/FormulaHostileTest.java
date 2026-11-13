package app.platform.sheets.internal.formula;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The hostile formulas that are too big to write in the case file (SH2, ADR-0252, ADR-0076, ADR-0081): the length
 * and the
 * nesting are checked before any parser sees the text, a flat sum of thousands of terms is refused for its size
 * and never
 * overflows the stack, and a formula that is allowed is calculated inside the bounds. The reasons are the ones the
 * browser's copy gives for the same texts.
 */
class FormulaHostileTest {

    private static final FormulaLimits LIMITS = FormulaLimits.DEFAULTS;

    private static String reasonOf(String stored) {
        return reasonOf(stored, LIMITS);
    }

    private static String reasonOf(String stored, FormulaLimits limits) {
        try {
            FormulaParser.parse(stored, limits);
        } catch (FormulaRefused refused) {
            return refused.reason();
        }
        return "accepted";
    }

    @Test
    void aFormulaOverFourThousandCharactersIsRefusedBeforeItIsParsed() {
        assertThat(reasonOf("1+".repeat(2000) + "1")).isEqualTo("too_long");
        assertThat(reasonOf("1".repeat(4001))).isEqualTo("too_long");
        assertThat(reasonOf("(".repeat(4001))).isEqualTo("too_long");
    }

    @Test
    void aFlatSumOfThousandsOfTermsIsRefusedForItsSizeAndNeverOverflowsTheStack() {
        assertThat(reasonOf("1+".repeat(1500) + "1")).isEqualTo("too_complex");
        assertThat(reasonOf("@{c1}+".repeat(600) + "1")).isEqualTo("too_complex");
        assertThat(reasonOf("SUM(" + "1,".repeat(1500) + "1)")).isEqualTo("too_complex");
    }

    @Test
    void nestingIsBoundedWhateverFormItTakes() {
        assertThat(reasonOf("(".repeat(33) + "1" + ")".repeat(33))).isEqualTo("too_deep");
        assertThat(reasonOf("-".repeat(40) + "1")).isEqualTo("too_deep");
        assertThat(reasonOf("ABS(".repeat(33) + "1" + ")".repeat(33))).isEqualTo("too_deep");
        assertThat(reasonOf("(".repeat(10000))).isEqualTo("too_long");
        assertThat(reasonOf("(".repeat(32) + "1" + ")".repeat(32))).isEqualTo("accepted");
    }

    @Test
    void aTextConstantMayHoldBracketsWithoutCountingThem() {
        assertThat(reasonOf("\"" + "(".repeat(100) + "\"")).isEqualTo("accepted");
    }

    @Test
    void aBoundThatIsNarrowedIsKept() {
        FormulaLimits narrow = new FormulaLimits(20, 3, 6, 100, 50, 100, 2, 2);
        assertThat(reasonOf("1+".repeat(10) + "1", narrow)).isEqualTo("too_long");
        assertThat(reasonOf("1+1+1+1+1+1+1", narrow)).isEqualTo("too_complex");
        assertThat(reasonOf("((((1))))", narrow)).isEqualTo("too_deep");
    }

    @Test
    void aDeepButAllowedFormulaIsCalculated() {
        Node tree = FormulaParser.parse("(".repeat(30) + "1+2" + ")".repeat(30), LIMITS);
        Object answer = FormulaEvaluator.evaluate(tree, "r1", CaseFile.grid(Map.of(), List.of()), LIMITS);
        assertThat(answer).isEqualTo(3.0);
    }

    @Test
    void aBoundOnStepsGivesTheLimitErrorAndNeverHangs() {
        FormulaLimits tight = new FormulaLimits(4000, 32, 1000, 50, 50, 100, 10, 500);
        Node tree = FormulaParser.parse("SUM(@{c1,r1}:@{c2,r40})", tight);
        Object answer = FormulaEvaluator.evaluate(tree, "r1", CaseFile.grid(Map.of(), List.of()), tight);
        assertThat(answer).isEqualTo(XlError.LIMIT);
    }

    @Test
    void aTextThatGrowsPastItsBoundGivesTheLimitError() {
        FormulaLimits tight = new FormulaLimits(4000, 32, 1000, 100_000, 10, 100, 10, 500);
        Node tree = FormulaParser.parse("CONCATENATE(\"abcdef\",\"abcdef\")", tight);
        assertThat(FormulaEvaluator.evaluate(tree, "r1", CaseFile.grid(Map.of(), List.of()), tight))
                .isEqualTo(XlError.LIMIT);
    }

    @Test
    void aWildcardCriterionCannotBeMadeToBlowUp() {
        String text = "a".repeat(5000);
        assertThat(FormulaValues.glob("*a*a*a*a*a*a*a*a*a*b", text)).isFalse();
        assertThat(FormulaValues.glob("*a*a*a*a*a*a*a*a*a*a", text)).isTrue();
    }

    @Test
    void aRefusalNeverRepeatsTheFormula() {
        String secret = "SECRETTEXT";
        List<String> formulas = List.of("\"" + secret, "MYNAME" + secret + "*2", secret + "(1)", "'" + secret + "'!A1",
                "@{SECRET TEXT}", "\"" + secret + "\"&1");
        for (String formula : formulas) {
            assertThatThrownBy(() -> FormulaParser.parse(formula, LIMITS)).isInstanceOfSatisfying(FormulaRefused.class,
                    refused -> {
                        assertThat(refused.getMessage()).doesNotContain(secret);
                        assertThat(FormulaWords.of(refused.reason())).doesNotContain(secret);
                    });
        }
    }

    @Test
    void theLimitsMustBePositive() {
        assertThatCode(() -> new FormulaLimits(1, 1, 1, 1, 1, 1, 1, 1)).doesNotThrowAnyException();
        assertThatThrownBy(() -> new FormulaLimits(0, 1, 1, 1, 1, 1, 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
