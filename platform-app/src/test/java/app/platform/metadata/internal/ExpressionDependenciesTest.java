package app.platform.metadata.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.expression.ExpressionEngine;
import app.platform.expression.Limits;
import app.platform.metadata.DefinitionKind;
import app.platform.metadata.FieldConfiguration;
import app.platform.metadata.FieldConfiguration.FormulaConfiguration;
import app.platform.metadata.FieldConfiguration.NoConfiguration;
import app.platform.metadata.FieldDefinition;
import app.platform.metadata.FieldType;
import app.platform.metadata.ObjectDefinition;
import app.platform.metadata.ValidationRule;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * What formula fields and validation rules need, as edges and violations of the dependency graph (ADR-0077, ADR-0078),
 * without a database: a field that is used cannot lose its place, and what does not pass the language, what is a circle
 * and what is stacked too high is refused naming the formula or the rule and the place.
 */
class ExpressionDependenciesTest {

    private static final ExpressionSupport SUPPORT = new ExpressionSupport(new ExpressionEngine(Limits.standard()),
            new MetadataProperties(
                    new MetadataProperties.Limits(200, 500, 1000, 50, 200, 50, 50, 20, 20, 200, 50, 5, 500, 4, 200, 50,
                            200, 1_000_000, 200, 50, 200, 10, 25, 1000),
                    new MetadataProperties.Cache(true, 1000)));
    private static final List<DependencyGraph.Contributor> CONTRIBUTORS = List.of(
            new ExpressionDependencies.Formulas(SUPPORT), new ExpressionDependencies.Rules(SUPPORT));

    private static FieldDefinition plain(String name, FieldType type) {
        return new FieldDefinition("Order__c", name, name, "", DefinitionKind.CUSTOM, type, false, false, null,
                new NoConfiguration(), false, 0);
    }

    private static FieldDefinition formula(String name, String expression, FieldType result, boolean verified) {
        FieldConfiguration configuration = new FormulaConfiguration(expression, result, verified);
        return new FieldDefinition("Order__c", name, name, "", DefinitionKind.CUSTOM, FieldType.FORMULA, false, false,
                null, configuration, false, 0);
    }

    private static ValidationRule rule(String name, String expression, String errorField) {
        return new ValidationRule("Order__c", name, name, "", expression, "Message", errorField, true, 0);
    }

    private static List<String> violations(List<FieldDefinition> extra, List<ValidationRule> rules) {
        List<FieldDefinition> fields = new ArrayList<>(List.of(plain("quantity__c", FieldType.NUMBER),
                plain("status__c", FieldType.TEXT)));
        fields.addAll(extra);
        ObjectDefinition object = new ObjectDefinition("Order__c", "Order", "Orders", "", DefinitionKind.CUSTOM, null,
                true, 0, fields);
        MetadataSnapshot snapshot = new MetadataSnapshot(List.of(object), Map.of(),
                new MetadataSnapshot.Definitions(Map.of(), Map.of(), Map.of(), List.of(),
                        Map.of("Order__c", rules)));
        return DependencyGraph.of(snapshot, CONTRIBUTORS).violations().stream()
                .map(DependencyGraph.Violation::message).toList();
    }

    // ---- validation rules ----

    @Test
    void aGoodRuleHasNoViolation() {
        assertThat(violations(List.of(), List.of(rule("NoNegative__c", "quantity__c < 0", "status__c")))).isEmpty();
    }

    @Test
    void aRuleThatUsesAMissingFieldIsRefusedByTheEdgeAndNamesTheRule() {
        assertThat(violations(List.of(), List.of(rule("Ghost__c", "ghost__c < 0", null)))).singleElement().asString()
                .isEqualTo("validation rule Ghost__c of Order__c uses field Order__c.ghost__c, which does not exist "
                        + "after this change.");
    }

    @Test
    void aRuleThatShowsItsMessageOnAMissingFieldIsRefused() {
        assertThat(violations(List.of(), List.of(rule("Shows__c", "quantity__c < 0", "gone__c")))).singleElement()
                .asString().isEqualTo("validation rule Shows__c of Order__c shows its message on field "
                        + "Order__c.gone__c, which does not exist after this change.");
    }

    @Test
    void aRuleThatDoesNotGiveTrueOrFalseOrBreaksTheLanguageIsRefusedWithThePlace() {
        List<String> found = violations(List.of(), List.of(rule("Number__c", "quantity__c + 1", null),
                rule("Typed__c", "quantity__c = 'ten'", null)));

        assertThat(found).hasSize(2);
        assertThat(found.get(0)).startsWith("Validation rule Number__c of Order__c is not valid. Line 1, column ")
                .contains("true or false");
        assertThat(found.get(1)).startsWith("Validation rule Typed__c of Order__c is not valid. Line 1, column 13: ")
                .contains("Cannot compare a number with text");
    }

    @Test
    void aRuleOfAnObjectThatIsGoneNamesTheObject() {
        ObjectDefinition other = new ObjectDefinition("Other__c", "Other", "Others", "", DefinitionKind.CUSTOM, null,
                true, 0, List.of());
        MetadataSnapshot snapshot = new MetadataSnapshot(List.of(other), Map.of(),
                new MetadataSnapshot.Definitions(Map.of(), Map.of(), Map.of(), List.of(),
                        Map.of("Order__c", List.of(rule("Orphan__c", "TRUE", null)))));

        assertThat(DependencyGraph.of(snapshot, CONTRIBUTORS).violations()).singleElement()
                .extracting(DependencyGraph.Violation::message).asString()
                .isEqualTo("validation rule Orphan__c of Order__c belongs to object Order__c, which does not exist "
                        + "after this change.");
    }

    // ---- formulas ----

    @Test
    void aGoodFormulaHasNoViolationAndItsFieldsAreEdges() {
        List<FieldDefinition> extra = List.of(formula("total__c", "quantity__c * 2", FieldType.NUMBER, true));
        ObjectDefinition object = new ObjectDefinition("Order__c", "Order", "Orders", "", DefinitionKind.CUSTOM, null,
                true, 0, List.of(plain("quantity__c", FieldType.NUMBER), extra.get(0)));
        DependencyGraph graph = DependencyGraph.of(new MetadataSnapshot(List.of(object), Map.of()), CONTRIBUTORS);

        assertThat(graph.violations()).isEmpty();
        assertThat(graph.dependentsOf(DependencyGraph.Node.field("Order__c", "quantity__c"))).singleElement()
                .satisfies(edge -> assertThat(edge.dependent().describe()).isEqualTo("field Order__c.total__c"));
    }

    @Test
    void aFormulaThatUsesAMissingFieldIsRefusedByTheEdgeAndNamesTheFormula() {
        assertThat(violations(List.of(formula("total__c", "gone__c * 2", FieldType.NUMBER, true)), List.of()))
                .singleElement().asString().isEqualTo("field Order__c.total__c uses field Order__c.gone__c, which "
                        + "does not exist after this change.");
    }

    @Test
    void aFormulaThatDoesNotGiveItsDeclaredTypeOrBreaksTheLanguageIsRefusedWithThePlace() {
        List<String> found = violations(List.of(
                formula("wrong__c", "status__c", FieldType.NUMBER, true),
                formula("fine__c", "quantity__c", FieldType.NUMBER, true),
                formula("broken__c", "quantity__c +", FieldType.NUMBER, true)), List.of());

        assertThat(found).hasSize(2);
        assertThat(found.get(0)).isEqualTo("Formula Order__c.wrong__c is not valid. Line 1, column 1: This gives "
                + "text, but it is declared to give a number.");
        assertThat(found.get(1)).startsWith("Formula Order__c.broken__c is not valid. Line 1, column 14: ");
    }

    @Test
    void aStoredFormulaThatWasNeverCheckedAndDoesNotPassIsNotAViolation() {
        assertThat(violations(List.of(formula("old__c", "quantity * 2", FieldType.NUMBER, false),
                formula("old2__c", "quantity__c +", FieldType.NUMBER, false)), List.of())).isEmpty();
    }

    @Test
    void aCircleIsOneViolationThatNamesTheFields() {
        List<String> found = violations(List.of(
                formula("a__c", "b__c + 1", FieldType.NUMBER, true),
                formula("b__c", "a__c + 1", FieldType.NUMBER, true)), List.of());

        assertThat(found).singleElement().asString().isEqualTo("Formulas of Order__c use each other in a circle: "
                + "Order__c.a__c -> Order__c.b__c -> Order__c.a__c. A formula cannot use itself, also not through "
                + "other formulas.");
    }

    @Test
    void aStackOfSixFormulasIsRefusedNamingTheChainAndTheLimit() {
        List<String> found = violations(List.of(
                formula("f1__c", "quantity__c", FieldType.NUMBER, true),
                formula("f2__c", "f1__c", FieldType.NUMBER, true),
                formula("f3__c", "f2__c", FieldType.NUMBER, true),
                formula("f4__c", "f3__c", FieldType.NUMBER, true),
                formula("f5__c", "f4__c", FieldType.NUMBER, true),
                formula("f6__c", "f5__c", FieldType.NUMBER, true)), List.of());

        assertThat(found).singleElement().asString().isEqualTo("Formula Order__c.f6__c is stacked on 6 formulas "
                + "(Order__c.f6__c -> Order__c.f5__c -> Order__c.f4__c -> Order__c.f3__c -> Order__c.f2__c -> "
                + "Order__c.f1__c); at most 5 formulas may be stacked.");
    }
}
