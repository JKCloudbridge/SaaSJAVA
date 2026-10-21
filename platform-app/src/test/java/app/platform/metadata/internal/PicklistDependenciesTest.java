package app.platform.metadata.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.metadata.DefinitionKind;
import app.platform.metadata.FieldConfiguration;
import app.platform.metadata.FieldConfiguration.Dependency;
import app.platform.metadata.FieldConfiguration.PicklistConfiguration;
import app.platform.metadata.FieldConfiguration.TextConfiguration;
import app.platform.metadata.FieldDefinition;
import app.platform.metadata.FieldType;
import app.platform.metadata.ObjectDefinition;
import app.platform.metadata.PicklistValue;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * What a dependent picklist needs from the other fields of its object (FA2A, ADR-0177), asked of the whole result of a
 * change, so the order of the changes of one set cannot matter: the edge to the controlling field, and a violation for
 * a controlling field that is the wrong kind of field, depends on another itself, or lacks a value the rule names.
 */
class PicklistDependenciesTest {

    private static final List<DependencyGraph.Contributor> CONTRIBUTORS = List.of(new PicklistDependencies());

    private static PicklistConfiguration list(Dependency dependency, String... values) {
        List<PicklistValue> all = new ArrayList<>();
        for (String value : values) {
            all.add(new PicklistValue(value, value, true));
        }
        return new PicklistConfiguration(all, dependency);
    }

    private static Dependency rule(String controlling, Map<String, List<String>> allowed) {
        return new Dependency(controlling, allowed);
    }

    private static FieldDefinition field(String name, FieldType type, FieldConfiguration configuration,
            boolean retired) {
        return new FieldDefinition("Doc__c", name, name, "", DefinitionKind.CUSTOM, type, false, false, null,
                configuration, retired, 0);
    }

    private static ObjectDefinition object(FieldDefinition... fields) {
        return new ObjectDefinition("Doc__c", "Doc", "Docs", "", DefinitionKind.CUSTOM, null, true, 0,
                List.of(fields));
    }

    private static DependencyGraph graph(ObjectDefinition object) {
        return DependencyGraph.of(new MetadataSnapshot(List.of(object), Map.of()), CONTRIBUTORS);
    }

    private static Map<String, List<String>> pairs(String key, String... values) {
        Map<String, List<String>> allowed = new LinkedHashMap<>();
        allowed.put(key, List.of(values));
        return allowed;
    }

    private static final FieldDefinition KIND = field("kind__c", FieldType.PICKLIST, list(null, "WI", "IMS"), false);

    @Test
    void aSoundDependencyHasOneEdgeToTheControllingFieldAndNoViolation() {
        DependencyGraph graph = graph(object(KIND, field("area__c", FieldType.PICKLIST,
                list(rule("kind__c", pairs("WI", "PRD")), "PRD", "QLT"), false)));

        assertThat(graph.violations()).isEmpty();
        assertThat(graph.edges()).hasSize(1);
        assertThat(graph.edges().get(0).dependent().describe()).isEqualTo("field Doc__c.area__c");
        assertThat(graph.edges().get(0).needs().describe()).isEqualTo("field Doc__c.kind__c");
    }

    @Test
    void aControllingFieldThatIsGoneIsReportedByTheEdgeInTheWordsOfEveryMissingNeed() {
        List<DependencyGraph.Violation> found = graph(object(field("area__c", FieldType.PICKLIST,
                list(rule("kind__c", pairs("WI", "PRD")), "PRD"), false))).violations();

        assertThat(found).hasSize(1);
        assertThat(found.get(0).message()).isEqualTo("field Doc__c.area__c depends on field Doc__c.kind__c, which "
                + "does not exist after this change.");
    }

    @Test
    void theOrderOfTheFieldsOfTheObjectDoesNotMatter() {
        FieldDefinition area = field("area__c", FieldType.PICKLIST, list(rule("kind__c", pairs("WI", "PRD")), "PRD"),
                false);

        assertThat(graph(object(KIND, area)).violations()).isEmpty();
        assertThat(graph(object(area, KIND)).violations()).isEmpty();
    }

    @Test
    void aControllingFieldThatIsNotASingleValuePicklistIsReported() {
        for (FieldDefinition other : List.of(field("kind__c", FieldType.MULTI_PICKLIST, list(null, "WI"), false),
                field("kind__c", FieldType.TEXT, new TextConfiguration(10), false))) {
            List<DependencyGraph.Violation> found = graph(object(other, field("area__c", FieldType.PICKLIST,
                    list(rule("kind__c", pairs("WI", "PRD")), "PRD"), false))).violations();

            assertThat(found).extracting(DependencyGraph.Violation::message)
                    .anyMatch(message -> message.contains("must be a single-value picklist"));
        }
    }

    @Test
    void aRetiredControllingFieldIsReported() {
        List<DependencyGraph.Violation> found = graph(object(field("kind__c", FieldType.PICKLIST,
                list(null, "WI"), true), field("area__c", FieldType.PICKLIST,
                        list(rule("kind__c", pairs("WI", "PRD")), "PRD"), false))).violations();

        assertThat(found).extracting(DependencyGraph.Violation::message)
                .anyMatch(message -> message.contains("still in use"));
    }

    @Test
    void aFieldThatDependsOnAFieldThatDependsOnAnotherIsReportedWhichAlsoKeepsACircleOut() {
        FieldDefinition one = field("one__c", FieldType.PICKLIST, list(rule("two__c", pairs("B", "A")), "A"), false);
        FieldDefinition two = field("two__c", FieldType.PICKLIST, list(rule("one__c", pairs("A", "B")), "B"), false);

        List<DependencyGraph.Violation> found = graph(object(one, two)).violations();

        assertThat(found).hasSize(2);
        assertThat(found).extracting(DependencyGraph.Violation::message)
                .allMatch(message -> message.contains("depends on another field itself"));
    }

    @Test
    void aChainOfThreeIsReported() {
        FieldDefinition root = field("root__c", FieldType.PICKLIST, list(null, "R"), false);
        FieldDefinition middle = field("middle__c", FieldType.PICKLIST, list(rule("root__c", pairs("R", "M")), "M"),
                false);
        FieldDefinition leaf = field("leaf__c", FieldType.PICKLIST, list(rule("middle__c", pairs("M", "L")), "L"),
                false);

        List<DependencyGraph.Violation> found = graph(object(root, middle, leaf)).violations();

        assertThat(found).hasSize(1);
        assertThat(found.get(0).dependent().describe()).isEqualTo("field Doc__c.leaf__c");
    }

    @Test
    void aFieldCannotDependOnItself() {
        List<DependencyGraph.Violation> found = graph(object(field("area__c", FieldType.PICKLIST,
                list(rule("area__c", pairs("PRD", "PRD")), "PRD"), false))).violations();

        assertThat(found).extracting(DependencyGraph.Violation::message)
                .anyMatch(message -> message.contains("cannot depend on itself"));
    }

    @Test
    void aKeyThatIsNotAValueOfTheControllingFieldAndAValueThePicklistLacksAreReportedWithoutTheText() {
        List<DependencyGraph.Violation> found = graph(object(KIND, field("area__c", FieldType.PICKLIST,
                list(rule("kind__c", pairs("SECRET-KEY", "SECRET-VALUE")), "PRD"), false))).violations();

        assertThat(found).hasSize(2);
        assertThat(found).extracting(DependencyGraph.Violation::message)
                .noneMatch(message -> message.contains("SECRET"));
    }

    @Test
    void aControllingValueThatIsSwitchedOffStillCountsAsAValue() {
        FieldDefinition kind = field("kind__c", FieldType.PICKLIST, new PicklistConfiguration(List.of(
                new PicklistValue("WI", "WI", false))), false);

        assertThat(graph(object(kind, field("area__c", FieldType.PICKLIST,
                list(rule("kind__c", pairs("WI", "PRD")), "PRD"), false))).violations()).isEmpty();
    }

    @Test
    void aFieldThatDependsOnNothingContributesNothing() {
        assertThat(graph(object(KIND)).edges()).isEmpty();
        assertThat(graph(object(KIND)).violations()).isEmpty();
    }
}
