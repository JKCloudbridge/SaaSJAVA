package app.platform.metadata.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.expression.ExpressionEngine;
import app.platform.expression.Limits;
import app.platformapi.WorkflowActionSpec;
import app.platformapi.WorkflowAssignmentSpec;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * What can be said of the actions of a workflow from the request alone (ADR-0138, ADR-0142), without a database: the
 * types of this release and no other, the settings each takes, the assignments only where a type takes them (each field
 * once, a value or a copy and never both, bounded), the attempts that can only be lowered, and the bound on the number
 * of actions. Every problem is kept under its place and none repeats what was typed.
 */
class WorkflowActionRulesTest {

    private static final MetadataProperties PROPERTIES = new MetadataProperties(
            new MetadataProperties.Limits(200, 500, 1000, 50, 200, 50, 50, 20, 20, 200, 50, 5, 500, 4, 200, 50, 200,
                    1_000_000, 200, 50, 200, 3, 6, 12, 10, 200, 10, 10, 20),
            new MetadataProperties.Cache(true, 1000));
    private static final WorkflowRules RULES = new WorkflowRules(
            new ExpressionSupport(new ExpressionEngine(Limits.standard()), PROPERTIES),
            Clock.fixed(Instant.parse("2026-10-17T12:00:00Z"), ZoneOffset.UTC), PROPERTIES,
            new WorkflowPlatformLimits(4, 5, 10));

    private static WorkflowAssignmentSpec literal(String field, String value) {
        return new WorkflowAssignmentSpec(field, value, null);
    }

    private static WorkflowAssignmentSpec copy(String field, String from) {
        return new WorkflowAssignmentSpec(field, null, from);
    }

    private static WorkflowActionSpec update(String name, WorkflowAssignmentSpec... assignments) {
        return new WorkflowActionSpec("UPDATE_RECORD", name, Map.of(), List.of(assignments), null);
    }

    /** What the rules made of a list of actions: the problems by place, and the actions kept (none when refused). */
    private record Outcome(Map<String, List<String>> problems, List<WorkflowActionSpec> kept) {
    }

    private static Outcome check(List<WorkflowActionSpec> actions) {
        Problems problems = new Problems();
        List<WorkflowActionSpec> kept = RULES.checkActions(problems, actions);
        return new Outcome(problems.all(), kept);
    }

    private static Map<String, List<String>> problemsOf(WorkflowActionSpec... actions) {
        return check(List.of(actions)).problems();
    }

    private static List<WorkflowActionSpec> kept(WorkflowActionSpec... actions) {
        Outcome outcome = check(List.of(actions));
        assertThat(outcome.problems()).isEmpty();
        return outcome.kept();
    }

    @Test
    void theActionsOfThisReleaseAreAcceptedAndNormalised() {
        List<WorkflowActionSpec> found = kept(
                update(" Set ", literal("status__c", "done"), copy("note__c", "status__c")),
                new WorkflowActionSpec("CREATE_RECORD", "Make", Map.of("object", "Task__c", "linkField", "order__c"),
                        List.of(literal("title__c", "x")), 3),
                new WorkflowActionSpec("SEND_EMAIL", "Tell", Map.of("recipientField", "owner__c",
                        "template", "RECORD_NOTICE"), List.of(), null));

        assertThat(found).extracting(WorkflowActionSpec::name).containsExactly("Set", "Make", "Tell");
        assertThat(found.get(0).assignments()).extracting(WorkflowAssignmentSpec::field)
                .containsExactly("status__c", "note__c");
        assertThat(found.get(1).maxAttempts()).isEqualTo(3);
        assertThat(found.get(2).assignments()).isEmpty();
    }

    @Test
    void aTypeOfALaterReleaseIsRefusedAndTheAnswerListsWhatThisReleaseHas() {
        for (String type : List.of("CALL_INTEGRATION", "RUN_SCRIPT")) {
            Map<String, List<String>> found = problemsOf(new WorkflowActionSpec(type, "X", Map.of()));

            assertThat(found.get("actions[0].type")).singleElement().asString().contains("UPDATE_RECORD")
                    .contains("CREATE_RECORD").contains("SEND_EMAIL").contains("PUBLISH_EVENT");
        }
    }

    @Test
    void anUpdateNeedsAFieldToSetAndAnActionThatSetsNothingTakesNoAssignments() {
        assertThat(problemsOf(update("Empty"))).containsKey("actions[0].assignments");
        assertThat(problemsOf(new WorkflowActionSpec("PUBLISH_EVENT", "Tell", Map.of("event", "a.b"),
                List.of(literal("status__c", "x")), null))).containsKey("actions[0].assignments");
        assertThat(problemsOf(new WorkflowActionSpec("SEND_EMAIL", "Tell", Map.of("recipientField", "owner__c",
                "template", "RECORD_NOTICE"), List.of(copy("a__c", "b__c")), null)))
                .containsKey("actions[0].assignments");
    }

    @Test
    void aFieldIsSetOnceAndEachAssignmentIsAValueOrACopyAndNeverBothOrNeither() {
        Map<String, List<String>> found = problemsOf(update("Set", literal("a__c", "1"), literal("a__c", "2"),
                new WorkflowAssignmentSpec("b__c", "x", "c__c"), new WorkflowAssignmentSpec("d__c", null, null),
                literal("e__c", "")));

        assertThat(found).containsKeys("actions[0].assignments[1].field", "actions[0].assignments[2]",
                "actions[0].assignments[3]", "actions[0].assignments[4].value");
    }

    @Test
    void theNamesOfFieldsAreAPIShapedAndALongLiteralIsRefused() {
        Map<String, List<String>> found = problemsOf(update("Set", literal("not a field", "x"),
                copy("a__c", "bad name"), literal("b__c", "x".repeat(13))));

        assertThat(found).containsKeys("actions[0].assignments[0].field", "actions[0].assignments[1].fromField",
                "actions[0].assignments[2].value");
    }

    @Test
    void anActionSetsAtMostTheLimitOfFieldsAndAWorkflowHasAtMostTheLimitOfActions() {
        assertThat(problemsOf(update("Set", literal("a__c", "1"), literal("b__c", "2"), literal("c__c", "3"),
                literal("d__c", "4"), literal("e__c", "5"), literal("f__c", "6"), literal("g__c", "7"))))
                .containsKey("actions[0].assignments");
        List<WorkflowActionSpec> four = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            four.add(update("S" + i, literal("a__c", "1")));
        }
        Outcome outcome = check(four);
        assertThat(outcome.problems()).containsKey("actions");
        assertThat(outcome.kept()).isNull();
    }

    @Test
    void theAttemptsOfAnActionCanBeLoweredAndNeverRaisedAboveThePlatformsNumber() {
        assertThat(kept(new WorkflowActionSpec("UPDATE_RECORD", "Set", Map.of(), List.of(literal("a__c", "1")), 4))
                .get(0).maxAttempts()).isEqualTo(4);
        assertThat(kept(new WorkflowActionSpec("UPDATE_RECORD", "Set", Map.of(), List.of(literal("a__c", "1")), 1))
                .get(0).maxAttempts()).isEqualTo(1);
        assertThat(problemsOf(new WorkflowActionSpec("UPDATE_RECORD", "Set", Map.of(), List.of(literal("a__c", "1")),
                5))).containsKey("actions[0].maxAttempts");
        assertThat(problemsOf(new WorkflowActionSpec("UPDATE_RECORD", "Set", Map.of(), List.of(literal("a__c", "1")),
                0))).containsKey("actions[0].maxAttempts");
    }

    @Test
    void theClosedTemplateIsTheOnlyOneAMailMayName() {
        Map<String, List<String>> found = problemsOf(new WorkflowActionSpec("SEND_EMAIL", "Tell",
                Map.of("recipientField", "owner__c", "template", "SIGN_UP_REQUEST"), List.of(), null),
                new WorkflowActionSpec("SEND_EMAIL", "Other", Map.of("recipientField", "owner__c"), List.of(), null));

        assertThat(found).containsKeys("actions[0].settings.template", "actions[1].settings.template");
    }

    @Test
    void aProblemNeverRepeatsWhatWasTyped() {
        Map<String, List<String>> found = problemsOf(update("Set", literal("SECRET-FIELD-NAME!", "SECRET-VALUE"),
                new WorkflowAssignmentSpec("ok__c", "SECRET-VALUE", "SECRET-FROM")));

        assertThat(found.toString()).doesNotContain("SECRET");
    }
}
