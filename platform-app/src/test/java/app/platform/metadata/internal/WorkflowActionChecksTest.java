package app.platform.metadata.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.expression.ExpressionEngine;
import app.platform.expression.Limits;
import app.platform.metadata.DefinitionKind;
import app.platform.metadata.FieldConfiguration;
import app.platform.metadata.FieldConfiguration.AutoNumberConfiguration;
import app.platform.metadata.FieldConfiguration.FormulaConfiguration;
import app.platform.metadata.FieldConfiguration.NoConfiguration;
import app.platform.metadata.FieldConfiguration.NumberConfiguration;
import app.platform.metadata.FieldConfiguration.ReferenceConfiguration;
import app.platform.metadata.FieldConfiguration.TextConfiguration;
import app.platform.metadata.FieldDefinition;
import app.platform.metadata.FieldType;
import app.platform.metadata.ObjectDefinition;
import app.platform.metadata.RecordSelectionChecker;
import app.platform.metadata.WorkflowDefinition;
import app.platform.metadata.WorkflowDefinition.Action;
import app.platform.metadata.WorkflowDefinition.Assignment;
import app.platform.metadata.WorkflowDefinition.Kind;
import app.platform.metadata.WorkflowDefinition.Trigger;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * What the actions of a workflow need from the objects they name (ADR-0138), without a database: every field an action
 * names is an edge of the dependency graph (so removing it is refused naming the workflow), and what is wrong with a
 * field, a literal, a copy or a created record's required fields is a violation naming the place. The messages say what
 * is wrong with a field and never repeat a literal.
 */
class WorkflowActionChecksTest {

    private static final ExpressionSupport SUPPORT = new ExpressionSupport(new ExpressionEngine(Limits.standard()),
            new MetadataProperties(
                    new MetadataProperties.Limits(200, 500, 1000, 50, 200, 50, 50, 20, 20, 200, 50, 5, 500, 4, 200, 50,
                            200, 1_000_000, 200, 50, 200, 10, 25, 1000, 10, 200, 10, 10, 20),
                    new MetadataProperties.Cache(true, 1000)));

    private static FieldDefinition field(String object, String name, FieldType type, FieldConfiguration config,
            boolean required, String defaultValue, DefinitionKind kind, boolean retired) {
        return new FieldDefinition(object, name, name, "", kind, type, required, false, defaultValue, config, retired,
                0);
    }

    private static FieldDefinition field(String object, String name, FieldType type) {
        return field(object, name, type, new NoConfiguration(), false, null, DefinitionKind.CUSTOM, false);
    }

    private static ObjectDefinition order() {
        return new ObjectDefinition("Order__c", "Order", "Orders", "", DefinitionKind.CUSTOM, null, true, 0, List.of(
                field("Order__c", "id", FieldType.TEXT, new TextConfiguration(40), false, null, DefinitionKind.SYSTEM,
                        false),
                field("Order__c", "status__c", FieldType.TEXT, new TextConfiguration(40), false, null,
                        DefinitionKind.CUSTOM, false),
                field("Order__c", "note__c", FieldType.LONG_TEXT, new TextConfiguration(1000), false, null,
                        DefinitionKind.CUSTOM, false),
                field("Order__c", "quantity__c", FieldType.NUMBER, new NumberConfiguration(9), false, null,
                        DefinitionKind.CUSTOM, false),
                field("Order__c", "closeDate__c", FieldType.DATE),
                field("Order__c", "approved__c", FieldType.BOOLEAN),
                field("Order__c", "contactEmail__c", FieldType.EMAIL),
                field("Order__c", "owner__c", FieldType.LOOKUP, new ReferenceConfiguration("User"), false, null,
                        DefinitionKind.CUSTOM, false),
                field("Order__c", "customer__c", FieldType.LOOKUP, new ReferenceConfiguration("Customer__c"), false,
                        null, DefinitionKind.CUSTOM, false),
                field("Order__c", "total__c", FieldType.FORMULA, new FormulaConfiguration("quantity__c * 2",
                        FieldType.NUMBER, true), false, null, DefinitionKind.CUSTOM, false),
                field("Order__c", "contactMail__c", FieldType.FORMULA, new FormulaConfiguration("contactEmail__c",
                        FieldType.EMAIL, true), false, null, DefinitionKind.CUSTOM, false),
                field("Order__c", "old__c", FieldType.TEXT, new TextConfiguration(40), false, null,
                        DefinitionKind.CUSTOM, true)));
    }

    private static ObjectDefinition task() {
        return new ObjectDefinition("Task__c", "Task", "Tasks", "", DefinitionKind.CUSTOM, null, true, 0, List.of(
                field("Task__c", "title__c", FieldType.TEXT, new TextConfiguration(80), true, null,
                        DefinitionKind.CUSTOM, false),
                field("Task__c", "kind__c", FieldType.TEXT, new TextConfiguration(40), true, "general",
                        DefinitionKind.CUSTOM, false),
                field("Task__c", "count__c", FieldType.NUMBER, new NumberConfiguration(9), false, null,
                        DefinitionKind.CUSTOM, false),
                field("Task__c", "order__c", FieldType.LOOKUP, new ReferenceConfiguration("Order__c"), false, null,
                        DefinitionKind.CUSTOM, false),
                field("Task__c", "other__c", FieldType.LOOKUP, new ReferenceConfiguration("Customer__c"), false, null,
                        DefinitionKind.CUSTOM, false),
                field("Task__c", "number__c", FieldType.AUTO_NUMBER, new AutoNumberConfiguration("T-", 1, 5), false,
                        null, DefinitionKind.CUSTOM, false)));
    }

    private static ObjectDefinition platformOwned() {
        return new ObjectDefinition("Person", "Person", "People", "", DefinitionKind.STANDARD, "identity", false, 0,
                List.of());
    }

    private static Action action(String type, String name, Map<String, String> settings,
            List<Assignment> assignments) {
        return new Action(type, name, settings, assignments, null);
    }

    private static Assignment literal(String field, String value) {
        return new Assignment(field, value, null);
    }

    private static Assignment copy(String field, String from) {
        return new Assignment(field, null, from);
    }

    private static WorkflowDefinition workflow(Action... actions) {
        return new WorkflowDefinition(UUID.randomUUID(), UUID.randomUUID(), "Order__c", "Flow__c", "Flow", "", true, 1,
                new Trigger(Kind.RECORD_UPDATED, List.of(), null, null, ZoneId.of("UTC"), LocalTime.MIDNIGHT, null,
                        null, null), null, List.of(actions), 0);
    }

    private static MetadataSnapshot snapshot(WorkflowDefinition workflow, ObjectDefinition... objects) {
        List<ObjectDefinition> all = new ArrayList<>(List.of(objects));
        return new MetadataSnapshot(all, Map.of(),
                new MetadataSnapshot.Definitions(Map.of(), Map.of(), Map.of(), List.of(), Map.of(), Map.of(),
                        List.of(), List.of(), Map.of(), Map.of("Order__c", List.of(workflow))));
    }

    private static DependencyGraph graph(WorkflowDefinition workflow, ObjectDefinition... objects) {
        return DependencyGraph.of(snapshot(workflow, objects), List.of(new WorkflowDependencies(SUPPORT,
                new StaticListableBeanFactory().getBeanProvider(RecordSelectionChecker.class))));
    }

    private static List<String> violations(Action action, ObjectDefinition... objects) {
        return graph(workflow(action), objects).violations().stream().map(DependencyGraph.Violation::message)
                .toList();
    }

    private static List<String> objects(Action action) {
        return violations(action, order(), task());
    }

    // ---- UPDATE_RECORD ----

    @Test
    void anUpdateOfFieldsThatExistAndFitIsValid() {
        assertThat(objects(action("UPDATE_RECORD", "Approve", Map.of(), List.of(literal("status__c", "Approved"),
                literal("quantity__c", "12"), literal("approved__c", "true"), literal("closeDate__c", "2030-01-31"),
                copy("note__c", "status__c"), copy("quantity__c", "total__c"))))).isEmpty();
    }

    @Test
    void everyFieldAnActionNamesIsAnEdgeSoItCannotBeRemovedFromUnderTheWorkflow() {
        DependencyGraph graph = graph(workflow(action("UPDATE_RECORD", "Set", Map.of(),
                List.of(literal("status__c", "A"), copy("note__c", "status__c")))), order(), task());

        assertThat(graph.violations()).isEmpty();
        assertThat(graph.dependentsOf(DependencyGraph.Node.field("Order__c", "status__c"))).hasSize(2);
        assertThat(graph.dependentsOf(DependencyGraph.Node.field("Order__c", "note__c"))).hasSize(1);
        assertThat(graph.dependentsOf(DependencyGraph.Node.field("Order__c", "status__c")))
                .extracting(edge -> edge.dependent().describe())
                .containsOnly("workflow Flow__c of Order__c");
    }

    @Test
    void aFieldThatIsGoneIsRefusedByTheEdgeAndNamesTheWorkflow() {
        assertThat(objects(action("UPDATE_RECORD", "Set", Map.of(), List.of(literal("gone__c", "A")))))
                .singleElement().asString().isEqualTo("workflow Flow__c of Order__c sets field Order__c.gone__c, "
                        + "which does not exist after this change.");
        assertThat(objects(action("UPDATE_RECORD", "Set", Map.of(), List.of(copy("status__c", "gone__c")))))
                .singleElement().asString().contains("copies from field Order__c.gone__c, which does not exist");
    }

    @Test
    void aFieldThePlatformWorksOutOrKeepsCannotBeSet() {
        for (String name : new String[] {"id", "total__c", "old__c"}) {
            assertThat(objects(action("UPDATE_RECORD", "Set", Map.of(), List.of(literal(name, "x")))))
                    .as(name).singleElement().asString().contains("actions[0].assignments[0].field");
        }
    }

    @Test
    void aLiteralMustFitTheFieldInTheWayADefaultValueMust() {
        for (Assignment wrong : new Assignment[] {literal("quantity__c", "ten"), literal("approved__c", "yes"),
            literal("closeDate__c", "31/01/2030"), literal("contactEmail__c", "not an address"),
            literal("status__c", "x".repeat(41))}) {
            List<String> found = objects(action("UPDATE_RECORD", "Set", Map.of(), List.of(wrong)));

            assertThat(found).as(wrong.field()).singleElement().asString()
                    .contains("actions[0].assignments[0].value");
        }
    }

    @Test
    void theMessageNeverRepeatsTheLiteral() {
        List<String> found = objects(action("UPDATE_RECORD", "Set", Map.of(),
                List.of(literal("quantity__c", "SECRET-VALUE-9"))));

        assertThat(found).singleElement().asString().doesNotContain("SECRET-VALUE-9");
    }

    @Test
    void aLinkIsNeverGivenALiteralButCanBeCopiedFromALinkToTheSameObject() {
        assertThat(objects(action("UPDATE_RECORD", "Set", Map.of(), List.of(literal("customer__c", "abc")))))
                .singleElement().asString().contains("assignments[0].value");
        assertThat(objects(action("UPDATE_RECORD", "Set", Map.of(), List.of(copy("customer__c", "customer__c")))))
                .isEmpty();
        assertThat(objects(action("UPDATE_RECORD", "Set", Map.of(), List.of(copy("customer__c", "owner__c")))))
                .singleElement().asString().contains("assignments[0].fromField");
    }

    @Test
    void aCopyMustBeOfATypeTheTargetTakes() {
        assertThat(objects(action("UPDATE_RECORD", "Set", Map.of(), List.of(copy("quantity__c", "closeDate__c")))))
                .singleElement().asString().contains("assignments[0].fromField");
        assertThat(objects(action("UPDATE_RECORD", "Set", Map.of(), List.of(copy("approved__c", "status__c")))))
                .singleElement().asString().contains("assignments[0].fromField");
        assertThat(objects(action("UPDATE_RECORD", "Set", Map.of(), List.of(copy("status__c", "contactEmail__c")))))
                .as("an address is text").isEmpty();
        assertThat(objects(action("UPDATE_RECORD", "Set", Map.of(), List.of(copy("old__c", "status__c")))))
                .as("a retired field cannot be set").singleElement().asString().contains("assignments[0].field");
        assertThat(objects(action("UPDATE_RECORD", "Set", Map.of(), List.of(copy("status__c", "old__c")))))
                .as("a retired field cannot be copied").singleElement().asString().contains("assignments[0].fromField");
    }

    // ---- CREATE_RECORD ----

    @Test
    void aCreateOfAnObjectWithItsRequiredFieldsGivenIsValid() {
        assertThat(objects(action("CREATE_RECORD", "Follow", Map.of("object", "Task__c", "linkField", "order__c"),
                List.of(literal("title__c", "Call the customer"), copy("count__c", "quantity__c"))))).isEmpty();
    }

    @Test
    void aRequiredFieldWithNoValueAndNoDefaultIsNamedAsMissing() {
        List<String> found = objects(action("CREATE_RECORD", "Follow", Map.of("object", "Task__c"),
                List.of(literal("count__c", "1"))));

        assertThat(found).singleElement().asString().contains("actions[0].assignments")
                .contains("required field title__c").doesNotContain("kind__c");
    }

    @Test
    void theLinkFieldMustPointToTheObjectOfTheWorkflowAndIsNotAssignedAgain() {
        assertThat(objects(action("CREATE_RECORD", "Follow", Map.of("object", "Task__c", "linkField", "other__c"),
                List.of(literal("title__c", "x"))))).singleElement().asString()
                .contains("actions[0].settings.linkField");
        assertThat(objects(action("CREATE_RECORD", "Follow", Map.of("object", "Task__c", "linkField", "count__c"),
                List.of(literal("title__c", "x"))))).singleElement().asString()
                .contains("actions[0].settings.linkField");
        assertThat(objects(action("CREATE_RECORD", "Follow", Map.of("object", "Task__c", "linkField", "order__c"),
                List.of(literal("title__c", "x"), copy("order__c", "customer__c"))))).anyMatch(
                        problem -> problem.contains("actions[0].assignments[1].field")
                                && problem.contains("set by linkField"));
    }

    @Test
    void theObjectToCreateMustExistAndBeOneWhoseRecordsTheOrganizationKeeps() {
        assertThat(violations(action("CREATE_RECORD", "Follow", Map.of("object", "Gone__c"),
                List.of(literal("title__c", "x"))), order(), task())).singleElement().asString()
                .contains("creates records of object Gone__c, which does not exist");
        assertThat(violations(action("CREATE_RECORD", "Follow", Map.of("object", "Person"),
                List.of(literal("title__c", "x"))), order(), task(), platformOwned())).singleElement().asString()
                .contains("belong to the platform");
    }

    @Test
    void theFieldsOfACreateAreThoseOfTheCreatedObjectAndACopyIsOfTheTriggeringOne() {
        // title__c is a field of Task__c, not of Order__c: it is valid here and would be missing for an update.
        assertThat(objects(action("UPDATE_RECORD", "Set", Map.of(), List.of(literal("title__c", "x")))))
                .singleElement().asString().contains("sets field Order__c.title__c");
        assertThat(objects(action("CREATE_RECORD", "Follow", Map.of("object", "Task__c"),
                List.of(literal("title__c", "x"), copy("count__c", "nope__c"))))).singleElement().asString()
                .contains("copies from field Order__c.nope__c");
    }

    // ---- SEND_EMAIL ----

    @Test
    void theRecipientIsAnAddressFieldOrALinkToAUser() {
        for (String name : new String[] {"contactEmail__c", "owner__c", "contactMail__c"}) {
            assertThat(objects(action("SEND_EMAIL", "Tell", Map.of("recipientField", name,
                    "template", "RECORD_NOTICE"), List.of()))).as(name).isEmpty();
        }
        for (String name : new String[] {"status__c", "customer__c", "quantity__c", "old__c"}) {
            assertThat(objects(action("SEND_EMAIL", "Tell", Map.of("recipientField", name,
                    "template", "RECORD_NOTICE"), List.of()))).as(name).singleElement().asString()
                    .contains("actions[0].settings.recipientField");
        }
        assertThat(objects(action("SEND_EMAIL", "Tell", Map.of("recipientField", "gone__c",
                "template", "RECORD_NOTICE"), List.of()))).singleElement().asString()
                .contains("mails the person in field Order__c.gone__c, which does not exist");
    }

    @Test
    void anEventActionNeedsNothingOfTheObject() {
        assertThat(objects(action("PUBLISH_EVENT", "Announce", Map.of("event", "order.created"), List.of())))
                .isEmpty();
    }

    @Test
    void theTypeOfAFormulaIsItsResultType() {
        assertThat(WorkflowActionChecks.compatible(field("Order__c", "total__c", FieldType.FORMULA,
                new FormulaConfiguration("quantity__c * 2", FieldType.NUMBER, true), false, null,
                DefinitionKind.CUSTOM, false), field("Task__c", "count__c", FieldType.DECIMAL,
                new FieldConfiguration.DecimalConfiguration(10, 2), false, null, DefinitionKind.CUSTOM, false)))
                .isTrue();
    }
}
