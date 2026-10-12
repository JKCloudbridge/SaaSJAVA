package app.platform.expression;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.platform.expression.ExpressionException.Kind;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * Every bound of the engine is reached on purpose and must end in an error that names the bound, never in a crash, a
 * stack overflow or a run that does not stop (ADR-0076, ADR-0081). The hostile inputs are made here rather than kept
 * in the corpus file because they are too big to write down.
 */
class ExpressionLimitsTest {

    private static final Map<String, ExpressionType> FIELDS = ExpressionEngineTest.FIELDS;
    private static final Instant WHEN = Instant.EPOCH;

    private final ExpressionEngine engine = new ExpressionEngine(Limits.standard());

    /** An engine that accepts a very long text, so that the other bounds are the ones that are tested. */
    private final ExpressionEngine roomy = new ExpressionEngine(
            new Limits(5_000_000, 500, 32, 10_000, 100_000, 50));

    private static Kind kindOf(ExpressionEngine which, String text) {
        try {
            which.compile(text, FIELDS, Purpose.FORMULA);
        } catch (ExpressionException e) {
            return e.kind();
        }
        return null;
    }

    // ---- length ----

    @Test
    void aTextOfExactlyTheLimitIsAcceptedAndOneCharacterMoreIsNot() {
        String atLimit = "1" + " ".repeat(Limits.FORMULA_LENGTH - 1);

        assertThatCode(() -> engine.parse(atLimit)).doesNotThrowAnyException();
        assertThat(kindOf(engine, atLimit + " ")).isEqualTo(Kind.TOO_LONG);
    }

    @Test
    void anEnormousTextIsRefusedAtOnceAndNamesTheLimit() {
        String huge = "1+".repeat(500_000) + "1";

        assertThatThrownBy(() -> engine.parse(huge)).isInstanceOfSatisfying(ExpressionException.class, e -> {
            assertThat(e.kind()).isEqualTo(Kind.TOO_LONG);
            assertThat(e.problem()).contains("4000");
        });
    }

    @Test
    void aVeryLongTextLiteralIsRefusedAsTooLong() {
        assertThat(kindOf(engine, "'" + "a".repeat(200_000) + "'")).isEqualTo(Kind.TOO_LONG);
        assertThat(kindOf(roomy, "'" + "a".repeat(200_000) + "'")).isEqualTo(Kind.TEXT_TOO_LONG);
    }

    @Test
    void aNameLongerThanEightyCharactersIsRefused() {
        assertThat(kindOf(engine, "a".repeat(81))).isEqualTo(Kind.SYNTAX);
        assertThat(kindOf(engine, "a".repeat(80))).isEqualTo(Kind.UNKNOWN_FIELD);
    }

    // ---- parts ----

    @Test
    void theTreeMayHaveFiveHundredPartsAndNotMore() {
        String fits = "1" + "+1".repeat(249);
        String tooMany = "1" + "+1".repeat(250);

        assertThat(engine.parse(fits).size()).isEqualTo(499);
        assertThat(kindOf(engine, tooMany)).isEqualTo(Kind.TOO_MANY_NODES);
        assertThatThrownBy(() -> engine.parse(tooMany)).isInstanceOfSatisfying(ExpressionException.class,
                e -> assertThat(e.problem()).contains("500 parts"));
    }

    @Test
    void manyCallsAreRefusedByTheCountOfPartsLongBeforeTheyCouldRun() {
        assertThat(kindOf(engine, "LEN('a')+".repeat(300) + "1")).isEqualTo(Kind.TOO_MANY_NODES);
        assertThat(kindOf(roomy, "LEN('a')+".repeat(300_000) + "1")).isEqualTo(Kind.TOO_MANY_NODES);
    }

    // ---- nesting: refused by a counter, so the recursion is never deep ----

    @Test
    void bracketsMayNestThirtyTwoLevelsAndNotMore() {
        assertThat(kindOf(engine, "(".repeat(32) + "1" + ")".repeat(32))).isNull();
        assertThat(kindOf(engine, "(".repeat(33) + "1" + ")".repeat(33))).isEqualTo(Kind.TOO_DEEP);
    }

    @Test
    void hostileNestingNeverOverflowsTheStack() {
        int many = 200_000;

        assertThat(kindOf(roomy, "(".repeat(many) + "1" + ")".repeat(many))).isEqualTo(Kind.TOO_DEEP);
        assertThat(kindOf(roomy, "-".repeat(many) + "1")).isEqualTo(Kind.TOO_DEEP);
        assertThat(kindOf(roomy, "NOT ".repeat(many) + "TRUE")).isEqualTo(Kind.TOO_DEEP);
        assertThat(kindOf(roomy, "ABS(".repeat(many) + "1" + ")".repeat(many))).isEqualTo(Kind.TOO_DEEP);
        assertThat(kindOf(roomy, "IF(TRUE, ".repeat(many) + "1")).isEqualTo(Kind.TOO_DEEP);
        assertThat(kindOf(roomy, "1 + (".repeat(many) + "1")).isEqualTo(Kind.TOO_DEEP);
    }

    @Test
    void aLongChainOfOperatorsIsALoopAndNotARecursion() {
        String chain = "1" + "+1".repeat(240);
        Compiled compiled = engine.compile(chain, FIELDS, Purpose.FORMULA);

        assertThat(engine.evaluate(compiled, Map.of(), WHEN)).isEqualTo(new BigDecimal(241));
    }

    // ---- evaluation: steps, text size, time ----

    @Test
    void theStepBoundStopsAnEvaluationThatDoesTooMuch() {
        ExpressionEngine small = new ExpressionEngine(new Limits(4000, 500, 32, 10, 100_000, 1_000));
        Compiled compiled = small.compile("1+1+1+1+1+1+1+1+1+1+1", FIELDS, Purpose.FORMULA);

        assertThatThrownBy(() -> small.evaluate(compiled, Map.of(), WHEN))
                .isInstanceOfSatisfying(ExpressionException.class, e -> {
                    assertThat(e.kind()).isEqualTo(Kind.TOO_MANY_STEPS);
                    assertThat(e.problem()).contains("10 steps");
                });
    }

    @Test
    void workOnALongTextCostsMoreSteps() {
        ExpressionEngine tight = new ExpressionEngine(new Limits(4000, 500, 32, 100, 100_000, 1_000));
        Compiled compiled = tight.compile("LEN(Name)", FIELDS, Purpose.FORMULA);

        assertThat(tight.evaluate(compiled, Map.of("Name", "a".repeat(5_000)), WHEN))
                .isEqualTo(new BigDecimal(5_000));
        assertThatThrownBy(() -> tight.evaluate(compiled, Map.of("Name", "a".repeat(50_000)), WHEN))
                .isInstanceOfSatisfying(ExpressionException.class,
                        e -> assertThat(e.kind()).isEqualTo(Kind.TOO_MANY_STEPS));
    }

    @Test
    void theClockBoundStopsAnEvaluationThatTakesTooLong() {
        AtomicLong nanos = new AtomicLong();
        ExpressionEngine slow = new ExpressionEngine(Limits.standard(), () -> nanos.addAndGet(20_000_000L));
        Compiled compiled = slow.compile("1+1+1+1", FIELDS, Purpose.FORMULA);

        assertThatThrownBy(() -> slow.evaluate(compiled, Map.of(), WHEN))
                .isInstanceOfSatisfying(ExpressionException.class, e -> {
                    assertThat(e.kind()).isEqualTo(Kind.TOO_SLOW);
                    assertThat(e.problem()).contains("50 milliseconds");
                });
    }

    @Test
    void aTextThatWouldGrowPastTheLimitIsAnErrorBeforeItIsBuilt() {
        String twenty = "CONCAT(" + "Name, ".repeat(19) + "Name)";
        String expression = "CONCAT(" + (twenty + ", ").repeat(19) + twenty + ")";
        Compiled compiled = engine.compile(expression, FIELDS, Purpose.FORMULA);

        assertThatThrownBy(() -> engine.evaluate(compiled, Map.of("Name", "x".repeat(1_000)), WHEN))
                .isInstanceOfSatisfying(ExpressionException.class,
                        e -> assertThat(e.kind()).isEqualTo(Kind.TEXT_TOO_LONG));
        assertThat(engine.evaluate(compiled, Map.of("Name", "x"), WHEN)).isEqualTo("x".repeat(400));
    }

    @Test
    void upperCaseOfATextThatGrowsIsCheckedAgainstTheLimit() {
        ExpressionEngine tiny = new ExpressionEngine(new Limits(4000, 500, 32, 10_000, 10, 1_000));
        Compiled compiled = tiny.compile("UPPER(Name)", FIELDS, Purpose.FORMULA);

        // The German sharp s becomes two letters in capitals, so six of them grow from 6 to 12 characters.
        assertThatThrownBy(() -> tiny.evaluate(compiled, Map.of("Name", "ß".repeat(6)), WHEN))
                .isInstanceOfSatisfying(ExpressionException.class,
                        e -> assertThat(e.kind()).isEqualTo(Kind.TEXT_TOO_LONG));
    }

    @Test
    void aSearchInATextBuiltToBeSlowStaysFast() {
        String text = "a".repeat(99_999) + "b";
        String needle = "a".repeat(50_000) + "b";
        ExpressionEngine generous = new ExpressionEngine(new Limits(4000, 500, 32, 100_000, 100_000, 5_000));
        Compiled compiled = generous.compile("CONTAINS(Name, Stage)", FIELDS, Purpose.FORMULA);

        long started = System.nanoTime();
        Object found = generous.evaluate(compiled, Map.of("Name", text, "Stage", needle), WHEN);
        long millis = (System.nanoTime() - started) / 1_000_000;

        assertThat(found).isEqualTo(true);
        assertThat(millis).as("a linear search of 150,000 characters").isLessThan(1_000);
    }

    @Test
    void everyLimitMustBePositive() {
        assertThatThrownBy(() -> new Limits(0, 1, 1, 1, 1, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Limits(1, 1, 1, 1, 1, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
