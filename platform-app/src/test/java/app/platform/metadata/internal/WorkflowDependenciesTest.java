package app.platform.metadata.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.expression.ExpressionEngine;
import app.platform.expression.Limits;
import app.platform.metadata.DefinitionKind;
import app.platform.metadata.FieldConfiguration.NoConfiguration;
import app.platform.metadata.FieldDefinition;
import app.platform.metadata.FieldType;
import app.platform.metadata.ObjectDefinition;
import app.platform.metadata.RecordSelectionChecker;
import app.platform.metadata.WorkflowDefinition;
import app.platform.metadata.WorkflowDefinition.Action;
import app.platform.metadata.WorkflowDefinition.Kind;
import app.platform.metadata.WorkflowDefinition.Trigger;
import app.platformapi.QueryFilterNode;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * What workflows need, as edges and violations of the dependency graph (ADR-0136), without a database: a field or an
 * object that a workflow names cannot lose its place, a date trigger needs a date, and what does not pass the language
 * or the query model is refused naming the workflow and the place.
 */
class WorkflowDependenciesTest {

    private static final ExpressionSupport SUPPORT = new ExpressionSupport(new ExpressionEngine(Limits.standard()),
            new MetadataProperties(
                    new MetadataProperties.Limits(200, 500, 1000, 50, 200, 50, 50, 20, 20, 200, 50, 5, 500, 4, 200, 50,
                            200, 1_000_000, 200, 50, 200, 10, 25, 1000, 10, 200, 10, 10, 20),
                    new MetadataProperties.Cache(true, 1000)));
    private static final List<Action> ACTIONS = List.of(new Action("PUBLISH_EVENT", "Announce",
            Map.of("event", "order.created")));

    private static FieldDefinition field(String name, FieldType type) {
        return new FieldDefinition("Order__c", name, name, "", DefinitionKind.CUSTOM, type, false, false, null,
                new NoConfiguration(), false, 0);
    }

    private static ObjectDefinition order() {
        return new ObjectDefinition("Order__c", "Order", "Orders", "", DefinitionKind.CUSTOM, null, true, 0,
                List.of(field("quantity__c", FieldType.NUMBER), field("status__c", FieldType.TEXT),
                        field("closeDate__c", FieldType.DATE), field("stamp__c", FieldType.DATETIME)));
    }

    private static Trigger trigger(Kind kind, List<String> fields, String field, QueryFilterNode selection) {
        return new Trigger(kind, fields, field, field == null ? null : 7, ZoneId.of("UTC"), LocalTime.MIDNIGHT, null,
                null, selection);
    }

    private static WorkflowDefinition workflow(String name, Trigger trigger, String condition) {
        return new WorkflowDefinition(UUID.randomUUID(), UUID.randomUUID(), "Order__c", name, name, "", true, 1,
                trigger, condition, ACTIONS, 0);
    }

    private static List<String> violations(ObjectDefinition object, RecordSelectionChecker checker,
            WorkflowDefinition... workflows) {
        MetadataSnapshot snapshot = new MetadataSnapshot(List.of(object), Map.of(),
                new MetadataSnapshot.Definitions(Map.of(), Map.of(), Map.of(), List.of(), Map.of(), Map.of(),
                        List.of(), List.of(), Map.of(), Map.of("Order__c", List.of(workflows))));
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        if (checker != null) {
            beans.addBean("checker", checker);
        }
        ObjectProvider<RecordSelectionChecker> provider = beans.getBeanProvider(RecordSelectionChecker.class);
        return DependencyGraph.of(snapshot, List.of(new WorkflowDependencies(SUPPORT, provider))).violations().stream()
                .map(DependencyGraph.Violation::message).toList();
    }

    @Test
    void aGoodWorkflowHasNoViolationAndItsFieldsAreEdges() {
        WorkflowDefinition good = workflow("Good__c", trigger(Kind.FIELD_CHANGED, List.of("status__c"), null, null),
                "quantity__c > 10");
        MetadataSnapshot snapshot = new MetadataSnapshot(List.of(order()), Map.of(),
                new MetadataSnapshot.Definitions(Map.of(), Map.of(), Map.of(), List.of(), Map.of(), Map.of(),
                        List.of(), List.of(), Map.of(), Map.of("Order__c", List.of(good))));
        DependencyGraph graph = DependencyGraph.of(snapshot, List.of(new WorkflowDependencies(SUPPORT,
                new StaticListableBeanFactory().getBeanProvider(RecordSelectionChecker.class))));

        assertThat(graph.violations()).isEmpty();
        assertThat(graph.dependentsOf(DependencyGraph.Node.field("Order__c", "status__c"))).singleElement()
                .satisfies(edge -> assertThat(edge.dependent().describe()).isEqualTo("workflow Good__c of Order__c"));
        assertThat(graph.dependentsOf(DependencyGraph.Node.field("Order__c", "quantity__c"))).hasSize(1);
    }

    @Test
    void aFieldChangeOnAFieldThatIsGoneIsRefusedAndNamesTheWorkflow() {
        assertThat(violations(order(), null,
                workflow("Watch__c", trigger(Kind.FIELD_CHANGED, List.of("gone__c"), null, null), null)))
                .singleElement().asString().isEqualTo("workflow Watch__c of Order__c starts when it changes field "
                        + "Order__c.gone__c, which does not exist after this change.");
    }

    @Test
    void aConditionThatUsesAFieldThatIsGoneIsRefusedByTheEdgeAndNamesTheWorkflow() {
        assertThat(violations(order(), null,
                workflow("Ghost__c", trigger(Kind.RECORD_CREATED, List.of(), null, null), "ghost__c > 1")))
                .singleElement().asString().isEqualTo("workflow Ghost__c of Order__c uses field Order__c.ghost__c, "
                        + "which does not exist after this change.");
    }

    @Test
    void aConditionThatDoesNotGiveTrueOrFalseOrBreaksTheLanguageIsRefusedWithThePlace() {
        List<String> found = violations(order(), null,
                workflow("Number__c", trigger(Kind.RECORD_CREATED, List.of(), null, null), "quantity__c + 1"),
                workflow("Typed__c", trigger(Kind.RECORD_CREATED, List.of(), null, null), "quantity__c = 'ten'"));

        assertThat(found).hasSize(2);
        assertThat(found.get(0)).startsWith("Workflow Number__c of Order__c has a condition that is not valid. "
                + "Line 1, column ").contains("workflow condition must give true or false");
        assertThat(found.get(1)).startsWith("Workflow Typed__c of Order__c has a condition that is not valid. "
                + "Line 1, column 13: ").contains("Cannot compare a number with text");
    }

    @Test
    void aConditionMayUseTheClock() {
        assertThat(violations(order(), null,
                workflow("Late__c", trigger(Kind.EVERY_DAY, List.of(), null, null), "closeDate__c < TODAY()")))
                .isEmpty();
    }

    @Test
    void theDaysAfterTriggerNeedsADateOrADateAndTime() {
        assertThat(violations(order(), null, workflow("Date__c",
                trigger(Kind.DAYS_AFTER_DATE, List.of(), "closeDate__c", null), null))).isEmpty();
        assertThat(violations(order(), null, workflow("Stamp__c",
                trigger(Kind.DAYS_AFTER_DATE, List.of(), "stamp__c", null), null))).isEmpty();
        assertThat(violations(order(), null, workflow("Text__c",
                trigger(Kind.DAYS_AFTER_DATE, List.of(), "status__c", null), null))).singleElement().asString()
                .isEqualTo("Workflow Text__c of Order__c counts days from Order__c.status__c, which is not a date or "
                        + "a date and time.");
        assertThat(violations(order(), null, workflow("Gone__c",
                trigger(Kind.DAYS_AFTER_DATE, List.of(), "gone__c", null), null))).singleElement().asString()
                .isEqualTo("workflow Gone__c of Order__c counts days from field Order__c.gone__c, which does not "
                        + "exist after this change.");
    }

    @Test
    void aSelectionIsCheckedByTheQueryModelAndItsFieldsAreEdges() {
        QueryFilterNode selection = new QueryFilterNode("EQ", null, "status__c", List.of("Open"));
        RecordSelectionChecker refusing = (object, node) -> List.of(new RecordSelectionChecker.Problem(
                "selection.field", "That comparison is not offered for this field."));

        assertThat(violations(order(), (object, node) -> List.of(), workflow("Pick__c",
                trigger(Kind.EVERY_DAY, List.of(), null, selection), null))).isEmpty();
        assertThat(violations(order(), refusing, workflow("Pick__c",
                trigger(Kind.EVERY_DAY, List.of(), null, selection), null))).singleElement().asString()
                .isEqualTo("Workflow Pick__c of Order__c has a selection that is not valid (selection.field). That "
                        + "comparison is not offered for this field.");
        assertThat(violations(order(), null, workflow("Pick__c", trigger(Kind.EVERY_DAY, List.of(), null,
                new QueryFilterNode("EQ", null, "gone__c", List.of("x"))), null))).singleElement().asString()
                .isEqualTo("workflow Pick__c of Order__c selects records by field Order__c.gone__c, which does not "
                        + "exist after this change.");
    }

    @Test
    void aWorkflowOfAnObjectThatIsGoneNamesTheObject() {
        ObjectDefinition other = new ObjectDefinition("Other__c", "Other", "Others", "", DefinitionKind.CUSTOM, null,
                true, 0, List.of());
        MetadataSnapshot snapshot = new MetadataSnapshot(List.of(other), Map.of(),
                new MetadataSnapshot.Definitions(Map.of(), Map.of(), Map.of(), List.of(), Map.of(), Map.of(),
                        List.of(), List.of(), Map.of(), Map.of("Order__c", List.of(workflow("Orphan__c",
                                trigger(Kind.RECORD_CREATED, List.of(), null, null), null)))));

        assertThat(DependencyGraph.of(snapshot, List.of(new WorkflowDependencies(SUPPORT,
                new StaticListableBeanFactory().getBeanProvider(RecordSelectionChecker.class)))).violations())
                .singleElement().extracting(DependencyGraph.Violation::message).asString()
                .isEqualTo("workflow Orphan__c of Order__c belongs to object Order__c, which does not exist after "
                        + "this change.");
    }

    @Test
    void aSwitchedOffWorkflowStillNamesItsFields() {
        WorkflowDefinition off = new WorkflowDefinition(UUID.randomUUID(), UUID.randomUUID(), "Order__c", "Off__c",
                "Off", "", false, 1, trigger(Kind.FIELD_CHANGED, List.of("gone__c"), null, null), null, ACTIONS, 0);

        assertThat(violations(order(), null, off)).hasSize(1);
    }
}
