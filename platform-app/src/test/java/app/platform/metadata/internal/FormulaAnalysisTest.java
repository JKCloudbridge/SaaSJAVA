package app.platform.metadata.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.expression.ExpressionEngine;
import app.platform.expression.ExpressionException.Kind;
import app.platform.expression.Limits;
import app.platform.metadata.DefinitionKind;
import app.platform.metadata.FieldConfiguration;
import app.platform.metadata.FieldConfiguration.FormulaConfiguration;
import app.platform.metadata.FieldConfiguration.NoConfiguration;
import app.platform.metadata.FieldDefinition;
import app.platform.metadata.FieldType;
import app.platform.metadata.ObjectDefinition;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * What is true of the formula fields of an object (ADR-0077), without a database: which formulas pass, which fields
 * each uses, the circles, the stacks that are too high, the order of calculation, and what a formula that was stored
 * before the language existed is and is not trusted for.
 */
class FormulaAnalysisTest {

    private static final ExpressionEngine ENGINE = new ExpressionEngine(Limits.standard());

    private static FieldDefinition plain(String name, FieldType type) {
        return new FieldDefinition("Order__c", name, name, "", DefinitionKind.CUSTOM, type, false, false, null,
                new NoConfiguration(), false, 0);
    }

    private static FieldDefinition formula(String name, String expression, FieldType result, boolean verified) {
        FieldConfiguration configuration = new FormulaConfiguration(expression, result, verified);
        return new FieldDefinition("Order__c", name, name, "", DefinitionKind.CUSTOM, FieldType.FORMULA, false, false,
                null, configuration, false, 0);
    }

    private static ObjectDefinition object(FieldDefinition... extra) {
        List<FieldDefinition> fields = new ArrayList<>(List.of(plain("quantity__c", FieldType.NUMBER),
                plain("status__c", FieldType.TEXT), plain("closeDate__c", FieldType.DATE)));
        fields.addAll(List.of(extra));
        return new ObjectDefinition("Order__c", "Order", "Orders", "", DefinitionKind.CUSTOM, null, true, 0, fields);
    }

    private static FormulaAnalysis analyse(FieldDefinition... formulas) {
        return FormulaAnalysis.of(object(formulas), ENGINE, 5);
    }

    @Test
    void aFormulaThatPassesIsCompiledAndSaysWhichFieldsItUses() {
        FormulaAnalysis analysis = analyse(formula("total__c", "quantity__c * 2 + LEN(status__c)", FieldType.NUMBER,
                true));

        FormulaAnalysis.Entry entry = analysis.entries().get("total__c");
        assertThat(entry.valid()).isTrue();
        assertThat(entry.uses()).containsExactly("quantity__c", "status__c");
        assertThat(entry.compiled().fields()).containsExactly("quantity__c", "status__c");
        assertThat(analysis.circles()).isEmpty();
        assertThat(analysis.tooDeep()).isEmpty();
        assertThat(analysis.depthOf("total__c")).isEqualTo(1);
    }

    @Test
    void aFormulaWithAMistakeKeepsWhatCouldBeReadOfItAndSaysWhatIsWrong() {
        FormulaAnalysis analysis = analyse(
                formula("ghost__c", "missing__c * quantity__c", FieldType.NUMBER, true),
                formula("wrong__c", "status__c", FieldType.NUMBER, true),
                formula("broken__c", "quantity__c *", FieldType.NUMBER, true));

        FormulaAnalysis.Entry ghost = analysis.entries().get("ghost__c");
        assertThat(ghost.valid()).isFalse();
        assertThat(ghost.problem().kind()).isEqualTo(Kind.UNKNOWN_FIELD);
        assertThat(ghost.uses()).containsExactly("missing__c", "quantity__c");
        assertThat(analysis.entries().get("wrong__c").problem().kind()).isEqualTo(Kind.TYPE);
        assertThat(analysis.entries().get("broken__c").problem().kind()).isEqualTo(Kind.SYNTAX);
        assertThat(analysis.entries().get("broken__c").uses()).isEmpty();
    }

    @Test
    void aFormulaStoredBeforeTheLanguageIsTrustedOnlyWhenItPasses() {
        FormulaAnalysis analysis = analyse(
                formula("old__c", "quantity * 2", FieldType.NUMBER, false),
                formula("fine__c", "quantity__c * 2", FieldType.NUMBER, false),
                formula("checked__c", "missing__c * 2", FieldType.NUMBER, true));

        assertThat(analysis.entries().get("old__c").trusted()).isFalse();
        assertThat(analysis.entries().get("fine__c").trusted()).isTrue();
        assertThat(analysis.entries().get("checked__c").trusted()).as("saved under the checks").isTrue();
    }

    @Test
    void aFormulaThatUsesItselfIsACircle() {
        FormulaAnalysis analysis = analyse(formula("loop__c", "loop__c + 1", FieldType.NUMBER, true));

        assertThat(analysis.circles()).singleElement().satisfies(circle ->
                assertThat(circle.path()).containsExactly("loop__c", "loop__c"));
        assertThat(analysis.inCircle("loop__c")).isTrue();
        assertThat(analysis.calculationOrder()).isEmpty();
    }

    @Test
    void formulasThatUseEachOtherThroughSeveralStepsAreOneCircleNamedOnce() {
        FormulaAnalysis analysis = analyse(
                formula("one__c", "three__c + 1", FieldType.NUMBER, true),
                formula("two__c", "one__c + 1", FieldType.NUMBER, true),
                formula("three__c", "two__c + 1", FieldType.NUMBER, true),
                formula("apart__c", "quantity__c", FieldType.NUMBER, true));

        assertThat(analysis.circles()).singleElement().satisfies(circle ->
                assertThat(circle.path()).containsExactly("one__c", "three__c", "two__c", "one__c"));
        assertThat(analysis.inCircle("two__c")).isTrue();
        assertThat(analysis.inCircle("apart__c")).isFalse();
        assertThat(analysis.calculationOrder()).containsExactly("apart__c");
    }

    @Test
    void theOrderOfCalculationPutsWhatAFormulaUsesBeforeIt() {
        FormulaAnalysis analysis = analyse(
                formula("last__c", "middle__c + 1", FieldType.NUMBER, true),
                formula("middle__c", "first__c + 1", FieldType.NUMBER, true),
                formula("first__c", "quantity__c", FieldType.NUMBER, true));

        assertThat(analysis.calculationOrder()).containsExactly("first__c", "middle__c", "last__c");
        assertThat(analysis.depthOf("first__c")).isEqualTo(1);
        assertThat(analysis.depthOf("middle__c")).isEqualTo(2);
        assertThat(analysis.depthOf("last__c")).isEqualTo(3);
    }

    @Test
    void aStackOfMoreThanTheLimitNamesTheChainThatMakesIt() {
        FormulaAnalysis analysis = analyse(
                formula("f1__c", "quantity__c", FieldType.NUMBER, true),
                formula("f2__c", "f1__c", FieldType.NUMBER, true),
                formula("f3__c", "f2__c", FieldType.NUMBER, true),
                formula("f4__c", "f3__c", FieldType.NUMBER, true),
                formula("f5__c", "f4__c", FieldType.NUMBER, true),
                formula("f6__c", "f5__c + f1__c", FieldType.NUMBER, true));

        assertThat(analysis.tooDeep()).singleElement().satisfies(deep -> {
            assertThat(deep.field()).isEqualTo("f6__c");
            assertThat(deep.depth()).isEqualTo(6);
            assertThat(deep.chain()).containsExactly("f6__c", "f5__c", "f4__c", "f3__c", "f2__c", "f1__c");
        });
        assertThat(FormulaAnalysis.of(object(analysisFormulasOfFive()), ENGINE, 5).tooDeep()).isEmpty();
    }

    private static FieldDefinition[] analysisFormulasOfFive() {
        return new FieldDefinition[] {
            formula("f1__c", "quantity__c", FieldType.NUMBER, true),
            formula("f2__c", "f1__c", FieldType.NUMBER, true),
            formula("f3__c", "f2__c", FieldType.NUMBER, true),
            formula("f4__c", "f3__c", FieldType.NUMBER, true),
            formula("f5__c", "f4__c", FieldType.NUMBER, true)};
    }

    @Test
    void anObjectWithoutFormulasHasNothingToAnalyse() {
        FormulaAnalysis analysis = analyse();

        assertThat(analysis.entries()).isEmpty();
        assertThat(analysis.circles()).isEmpty();
        assertThat(analysis.calculationOrder()).isEmpty();
        assertThat(analysis.depthOf("anything__c")).isEqualTo(1);
    }

    @Test
    void aFormulaMayUseAFormulaWhoseTypeIsADifferentKindOfNumber() {
        FormulaAnalysis analysis = analyse(
                formula("price__c", "quantity__c * 3", FieldType.CURRENCY, true),
                formula("tax__c", "price__c * 0.2", FieldType.PERCENT, true),
                formula("when__c", "closeDate__c + 5", FieldType.DATE, true),
                formula("flag__c", "when__c > closeDate__c", FieldType.BOOLEAN, true));

        assertThat(analysis.entries().values()).allMatch(FormulaAnalysis.Entry::valid);
        assertThat(analysis.calculationOrder()).containsExactly("price__c", "tax__c", "when__c", "flag__c");
    }
}
