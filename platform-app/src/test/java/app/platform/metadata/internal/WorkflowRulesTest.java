package app.platform.metadata.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.expression.ExpressionEngine;
import app.platform.expression.Limits;
import app.platformapi.ApiException;
import app.platformapi.QueryFilterNode;
import app.platformapi.WorkflowActionSpec;
import app.platformapi.WorkflowTriggerSpec;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * What can be checked of a workflow from its request alone (ADR-0136), without a database: the kinds of trigger this
 * release accepts and the properties each uses, real time zones and times, a one-time moment that lies ahead, the
 * grammar of the condition, and the closed list of actions with the settings each takes. Every problem is kept under
 * its place and none repeats text a person typed.
 */
class WorkflowRulesTest {

    private static final Instant NOW = Instant.parse("2026-10-16T12:00:00Z");
    private static final ExpressionSupport SUPPORT = new ExpressionSupport(new ExpressionEngine(Limits.standard()),
            new MetadataProperties(
                    new MetadataProperties.Limits(200, 500, 1000, 50, 200, 50, 50, 20, 20, 200, 50, 5, 500, 4, 200, 50,
                            200, 1_000_000, 200, 50, 200, 10, 25, 1000, 10, 200, 10, 10, 20),
                    new MetadataProperties.Cache(true, 1000)));
    private static final WorkflowRules RULES = new WorkflowRules(SUPPORT, Clock.fixed(NOW, ZoneOffset.UTC),
            new MetadataProperties(new MetadataProperties.Limits(200, 500, 1000, 50, 200, 50, 50, 20, 20, 200, 50, 5,
                    500, 4, 200, 50, 200, 1_000_000, 200, 50, 200, 10, 25, 1000, 10, 200, 10, 10, 20),
                    new MetadataProperties.Cache(true, 1000)),
            new WorkflowPlatformLimits(5, 5));
    private static final List<WorkflowActionSpec> ONE_ACTION = List.of(signal("Announce", "order.created"));

    private static WorkflowActionSpec signal(String name, String event) {
        return new WorkflowActionSpec("PUBLISH_EVENT", name, Map.of("event", event));
    }

    private static WorkflowTriggerSpec kind(String kind) {
        return new WorkflowTriggerSpec(kind, null, null, null, null, null, null, null, null);
    }

    private static WorkflowTriggerSpec scheduled(String kind, String zone, String time, String day, String at) {
        return new WorkflowTriggerSpec(kind, null, null, null, zone, time, day, at, null);
    }

    /** The problems of the request by place; empty when it is valid. */
    private static Map<String, List<String>> problems(WorkflowTriggerSpec trigger, String condition,
            List<WorkflowActionSpec> actions, boolean future) {
        Problems found = new Problems();
        RULES.check(found, "Label", "", trigger, condition, actions, future);
        try {
            found.throwIfAny();
            return Map.of();
        } catch (ApiException refused) {
            return refused.fields();
        }
    }

    private static WorkflowRules.Checked checked(WorkflowTriggerSpec trigger, String condition,
            List<WorkflowActionSpec> actions) {
        Problems found = new Problems();
        WorkflowRules.Checked result = RULES.check(found, "  Label ", "", trigger, condition, actions, true);
        found.throwIfAny();
        return result;
    }

    // ---- triggers that are records changing ----

    @Test
    void aTriggerOfARecordEventNeedsNothingElseAndTheLabelAndConditionAreCleaned() {
        WorkflowRules.Checked result = checked(kind("RECORD_CREATED"), "  quantity__c > 10 \n", ONE_ACTION);

        assertThat(result.label()).isEqualTo("Label");
        assertThat(result.condition()).isEqualTo("quantity__c > 10");
        assertThat(result.trigger().kind()).isEqualTo("RECORD_CREATED");
        assertThat(result.trigger().timeZone()).isNull();
    }

    @Test
    void aBlankConditionMeansNoCondition() {
        assertThat(checked(kind("RECORD_UPDATED"), "   ", ONE_ACTION).condition()).isNull();
        assertThat(checked(kind("RECORD_UPDATED"), null, ONE_ACTION).condition()).isNull();
    }

    @Test
    void aTriggerFromAnExternalSystemIsNotAcceptedYet() {
        Map<String, List<String>> found = problems(kind("WEBHOOK"), null, ONE_ACTION, true);

        assertThat(found).containsOnlyKeys("trigger.kind");
        assertThat(found.get("trigger.kind").get(0)).contains("external systems are not available yet");
    }

    @Test
    void aPropertyTheKindDoesNotUseIsRefusedWithItsPlace() {
        WorkflowTriggerSpec trigger = new WorkflowTriggerSpec("RECORD_CREATED", List.of("status__c"), "closeDate__c", 3,
                "UTC", "09:00", "MONDAY", "2030-01-31T09:30", null);

        assertThat(problems(trigger, null, ONE_ACTION, true)).containsOnlyKeys("trigger.fields", "trigger.field",
                "trigger.days", "trigger.timeZone", "trigger.time", "trigger.dayOfWeek", "trigger.at");
    }

    @Test
    void aFieldChangeNeedsFieldsThatAreNamedOnce() {
        assertThat(problems(kind("FIELD_CHANGED"), null, ONE_ACTION, true)).containsOnlyKeys("trigger.fields");

        WorkflowTriggerSpec twice = new WorkflowTriggerSpec("FIELD_CHANGED", List.of("status__c", "status__c"), null,
                null, null, null, null, null, null);
        assertThat(problems(twice, null, ONE_ACTION, true)).containsOnlyKeys("trigger.fields[1]");

        WorkflowTriggerSpec bad = new WorkflowTriggerSpec("FIELD_CHANGED", List.of("not a field"), null, null, null,
                null, null, null, null);
        assertThat(problems(bad, null, ONE_ACTION, true)).containsOnlyKeys("trigger.fields[0]");

        WorkflowTriggerSpec good = new WorkflowTriggerSpec("FIELD_CHANGED", List.of("status__c", "amount__c"), null,
                null, null, null, null, null, null);
        assertThat(checked(good, null, ONE_ACTION).trigger().fields()).containsExactly("status__c", "amount__c");
    }

    // ---- scheduled triggers ----

    @Test
    void aDailyTriggerGetsUtcAndMidnightByDefaultAndTheTimeIsNormalised() {
        WorkflowTriggerSpec trigger = checked(kind("EVERY_DAY"), null, ONE_ACTION).trigger();
        WorkflowTriggerSpec set = checked(scheduled("EVERY_DAY", "Europe/Berlin", "09:05", null, null), null,
                ONE_ACTION).trigger();

        assertThat(trigger.timeZone()).isEqualTo("UTC");
        assertThat(trigger.time()).isEqualTo("00:00");
        assertThat(set.timeZone()).isEqualTo("Europe/Berlin");
        assertThat(set.time()).isEqualTo("09:05");
    }

    @Test
    void aTimeZoneThatIsNotRealAndATimeThatIsNotATimeAreRefused() {
        assertThat(problems(scheduled("EVERY_DAY", "Mars/Olympus", "09:00", null, null), null, ONE_ACTION, true))
                .containsOnlyKeys("trigger.timeZone");
        assertThat(problems(scheduled("EVERY_DAY", "UTC", "25:00", null, null), null, ONE_ACTION, true))
                .containsOnlyKeys("trigger.time");
        assertThat(problems(scheduled("EVERY_DAY", "UTC", "nine", null, null), null, ONE_ACTION, true))
                .containsOnlyKeys("trigger.time");
    }

    @Test
    void aWeeklyTriggerNeedsADayOfTheWeek() {
        assertThat(problems(scheduled("EVERY_WEEK", "UTC", "09:00", null, null), null, ONE_ACTION, true))
                .containsOnlyKeys("trigger.dayOfWeek");
        assertThat(problems(scheduled("EVERY_WEEK", "UTC", "09:00", "FUNDAY", null), null, ONE_ACTION, true))
                .containsOnlyKeys("trigger.dayOfWeek");
        assertThat(checked(scheduled("EVERY_WEEK", "UTC", "09:00", "monday", null), null, ONE_ACTION).trigger()
                .dayOfWeek()).isEqualTo("MONDAY");
    }

    @Test
    void aOneTimeMomentNeedsAMomentThatLiesAheadWhenItIsBeingSet() {
        assertThat(problems(scheduled("DATE_TIME", "UTC", null, null, null), null, ONE_ACTION, true))
                .containsOnlyKeys("trigger.at");
        assertThat(problems(scheduled("DATE_TIME", "UTC", null, null, "tomorrow"), null, ONE_ACTION, true))
                .containsOnlyKeys("trigger.at");
        assertThat(problems(scheduled("DATE_TIME", "UTC", null, null, "2026-10-16T11:59"), null, ONE_ACTION, true))
                .containsOnlyKeys("trigger.at");
        assertThat(problems(scheduled("DATE_TIME", "UTC", null, null, "2026-10-16T11:59"), null, ONE_ACTION, false))
                .as("a moment that was not changed and has passed is simply a workflow that already ran").isEmpty();
        assertThat(problems(scheduled("DATE_TIME", "Europe/Berlin", null, null, "2026-10-16T13:30"), null,
                ONE_ACTION, true)).as("13:30 in Berlin is 11:30 UTC, in the past").containsOnlyKeys("trigger.at");
        assertThat(problems(scheduled("DATE_TIME", "UTC", null, null, "2026-10-16T12:01"), null, ONE_ACTION, true))
                .isEmpty();
    }

    @Test
    void theDaysAfterTriggerNeedsAFieldAndANumberOfDaysInRange() {
        WorkflowTriggerSpec none = kind("DAYS_AFTER_DATE");
        WorkflowTriggerSpec tooMany = new WorkflowTriggerSpec("DAYS_AFTER_DATE", null, "closeDate__c", 4000, null, null,
                null, null, null);
        WorkflowTriggerSpec negative = new WorkflowTriggerSpec("DAYS_AFTER_DATE", null, "closeDate__c", -1, null,
                null, null, null, null);
        WorkflowTriggerSpec good = new WorkflowTriggerSpec("DAYS_AFTER_DATE", null, "closeDate__c", 30, "UTC",
                "06:00", null, null, null);

        assertThat(problems(none, null, ONE_ACTION, true)).containsOnlyKeys("trigger.field", "trigger.days");
        assertThat(problems(tooMany, null, ONE_ACTION, true)).containsOnlyKeys("trigger.days");
        assertThat(problems(negative, null, ONE_ACTION, true)).containsOnlyKeys("trigger.days");
        assertThat(checked(good, null, ONE_ACTION).trigger().days()).isEqualTo(30);
    }

    @Test
    void aSelectionIsForScheduledTriggersOnlyAndIsBounded() {
        QueryFilterNode condition = new QueryFilterNode("EQ", null, "status__c", List.of("Open"));
        WorkflowTriggerSpec forARecordEvent = new WorkflowTriggerSpec("RECORD_CREATED", null, null, null, null, null,
                null, null, condition);
        WorkflowTriggerSpec daily = new WorkflowTriggerSpec("EVERY_DAY", null, null, null, "UTC", "09:00", null, null,
                condition);
        QueryFilterNode deep = condition;
        for (int level = 0; level < 6; level++) {
            deep = new QueryFilterNode("AND", List.of(deep), null, null);
        }
        WorkflowTriggerSpec tooDeep = new WorkflowTriggerSpec("EVERY_DAY", null, null, null, "UTC", "09:00", null,
                null, deep);
        List<QueryFilterNode> many = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            many.add(condition);
        }
        WorkflowTriggerSpec tooWide = new WorkflowTriggerSpec("EVERY_DAY", null, null, null, "UTC", "09:00", null,
                null, new QueryFilterNode("OR", many, null, null));

        assertThat(problems(forARecordEvent, null, ONE_ACTION, true)).containsOnlyKeys("trigger.selection");
        assertThat(problems(daily, null, ONE_ACTION, true)).isEmpty();
        assertThat(problems(tooDeep, null, ONE_ACTION, true)).containsOnlyKeys("trigger.selection");
        assertThat(problems(tooWide, null, ONE_ACTION, true)).containsOnlyKeys("trigger.selection");
    }

    // ---- the condition ----

    @Test
    void aConditionThatBreaksTheGrammarIsRefusedWithThePlaceAndWithoutRepeatingIt() {
        Map<String, List<String>> found = problems(kind("RECORD_CREATED"), "quantity__c >\n  and", ONE_ACTION, true);

        assertThat(found).containsOnlyKeys("condition");
        assertThat(found.get("condition").get(0)).startsWith("Line 2, column 3: ").doesNotContain("quantity__c");
    }

    // ---- the actions ----

    @Test
    void aWorkflowNeedsAtLeastOneActionAndEachIsOfAKnownType() {
        assertThat(problems(kind("RECORD_CREATED"), null, List.of(), true)).containsOnlyKeys("actions");
        assertThat(problems(kind("RECORD_CREATED"), null, null, true)).containsOnlyKeys("actions");
        assertThat(problems(kind("RECORD_CREATED"), null,
                List.of(new WorkflowActionSpec("RUN_SCRIPT", "Script", Map.of())), true))
                .containsOnlyKeys("actions[0].type");
    }

    @Test
    void theNamesOfActionsAreShapedAndUniqueIgnoringCase() {
        assertThat(problems(kind("RECORD_CREATED"), null, List.of(signal("Same", "a.b"), signal("same", "a.c")), true))
                .containsOnlyKeys("actions[1].name");
        assertThat(problems(kind("RECORD_CREATED"), null, List.of(signal("1st step", "a.b")), true))
                .containsOnlyKeys("actions[0].name");
    }

    @Test
    void anEventActionNeedsAnEventNamedInLowerCaseWordsAndTakesNothingElse() {
        assertThat(problems(kind("RECORD_CREATED"), null,
                List.of(new WorkflowActionSpec("PUBLISH_EVENT", "Announce", Map.of())), true))
                .containsOnlyKeys("actions[0].settings.event");
        assertThat(problems(kind("RECORD_CREATED"), null, List.of(signal("Announce", "Order Created")), true))
                .containsOnlyKeys("actions[0].settings.event");
        assertThat(problems(kind("RECORD_CREATED"), null, List.of(new WorkflowActionSpec("PUBLISH_EVENT", "Announce",
                Map.of("event", "order.created", "payload", "x"))), true))
                .containsOnlyKeys("actions[0].settings.payload");
        assertThat(checked(kind("RECORD_CREATED"), null, List.of(signal("Announce", " order.created ")))
                .actions().get(0).settings()).containsEntry("event", "order.created");
    }

    @Test
    void everyProblemOfARequestIsKeptSoThePersonFixesThemAllAtOnce() {
        Map<String, List<String>> found = problems(scheduled("EVERY_WEEK", "Mars/Olympus", "25:00", null, null),
                "quantity__c >", List.of(), true);

        assertThat(found).containsOnlyKeys("trigger.timeZone", "trigger.time", "trigger.dayOfWeek", "condition",
                "actions");
    }

    // ---- the end of an approval ----

    private static WorkflowTriggerSpec approval(String process, String outcome) {
        return new WorkflowTriggerSpec("APPROVAL_OUTCOME", null, null, null, null, null, null, null, null, process,
                outcome);
    }

    @Test
    void anApprovalTriggerNamesAProcessAndAnOutcomeOrNamesNoneAndIsCleaned() {
        WorkflowRules.Checked named = checked(approval(" Large__c ", " approved "), null, ONE_ACTION);
        WorkflowRules.Checked any = checked(approval(null, null), null, ONE_ACTION);

        assertThat(named.trigger().process()).isEqualTo("Large__c");
        assertThat(named.trigger().outcome()).isEqualTo("APPROVED");
        assertThat(any.trigger().process()).isNull();
        assertThat(any.trigger().outcome()).isNull();
    }

    @Test
    void anApprovalTriggerRefusesAProcessNameThatIsNotOneAndAnOutcomeThatIsNotOneOfThree() {
        Map<String, List<String>> found = problems(approval("not a name", "SECRET-OUTCOME"), null, ONE_ACTION, true);

        assertThat(found).containsOnlyKeys("trigger.process", "trigger.outcome");
        assertThat(found.values().stream().flatMap(List::stream)).noneMatch(text -> text.contains("SECRET-"));
    }

    @Test
    void theApprovalPropertiesBelongToTheApprovalTriggerAlone() {
        WorkflowTriggerSpec trigger = new WorkflowTriggerSpec("RECORD_CREATED", null, null, null, null, null, null,
                null, null, "Large__c", "APPROVED");

        assertThat(problems(trigger, null, ONE_ACTION, true)).containsOnlyKeys("trigger.process", "trigger.outcome");
    }
}
