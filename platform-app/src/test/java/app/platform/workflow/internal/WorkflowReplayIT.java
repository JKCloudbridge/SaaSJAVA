package app.platform.workflow.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations.Organization;
import app.platform.testsupport.tenancy.TenantFixtures;
import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * Replay (ADR-0141, ADR-0143): a dead letter is run again from the step that failed, once, against the record as it is
 * now, without judging the condition again and without running a completed step again; two clicks make one new
 * execution; a bulk replay answers for each execution; and every endpoint is refused to who may not use it, to another
 * organization and to a feature the plan does not have.
 */
@PlatformIntegrationTest
@TestPropertySource(properties = {
    "platform.outbox.enabled=true",
    "platform.outbox.poll-interval=50ms",
    "platform.workflow.enabled=false"})
class WorkflowReplayIT extends ActionsSupport {

    private record Setup(Organization organization, TestBrowser admin, String record, String execution) {
    }

    /**
     * A dead letter: the first step announces an event and completes, the second tries to set a value that a rule
     * rejects. The rule stands in for "something a person must mend".
     */
    private Setup deadLetter(String condition) {
        Organization organization = organization();
        TestBrowser admin = adminWithData(organization);
        addRule(admin, "NoBad", "fieldB__c = 'bad'", "Not bad.");
        workflowExists(admin, "Flow", recordTrigger("RECORD_CREATED"), condition, action("Tell", "first.event"),
                update("Set", value("fieldB__c", "bad")), action("Last", "last.event"));
        String id = record(admin, "{\"fieldA__c\":\"hello\",\"quantity__c\":20}");
        settle(organization);
        runWorker();
        String execution = theExecution(organization);
        assertThat(execution(organization, execution)).containsEntry("status", "DEAD_LETTERED");
        assertThat(steps(organization, execution)).containsExactly("Tell:COMPLETED:1", "Set:FAILED:1");
        return new Setup(organization, admin, id, execution);
    }

    private static Response replay(TestBrowser admin, String execution) {
        return admin.postJson(EXECUTIONS + "/" + execution + "/replay", "{}");
    }

    // ---- the replay ----

    @Test
    void aReplayIsANewExecutionThatResumesAtTheFailedStepAndRunsOnlyWhatIsLeft() {
        Setup setup = deadLetter(null);
        removeRule(setup.admin(), "NoBad__c");

        Response answer = replay(setup.admin(), setup.execution());
        String replayed = JsonPath.read(answer.body(), "$.data.replay");
        runWorker();
        settle(setup.organization());

        assertThat(answer.status()).as(answer.body()).isEqualTo(200);
        assertThat(JsonPath.<String>read(answer.body(), "$.data.outcome")).isEqualTo("REPLAYED");
        assertThat(execution(setup.organization(), replayed)).containsEntry("status", "COMPLETED")
                .containsEntry("replay_of", setup.execution()).containsEntry("replay_generation", "1")
                .containsEntry("resume_step", "2");
        assertThat(steps(setup.organization(), replayed)).as("step 1 is not run again")
                .containsExactly("Set:COMPLETED:1", "Last:COMPLETED:1");
        assertThat(signals(setup.organization(), "workflow.signal.first.event")).as("not announced twice").isEqualTo(1);
        assertThat(signals(setup.organization(), "workflow.signal.last.event")).isEqualTo(1);
        assertThat(fieldOf(setup.admin(), setup.record(), "fieldB__c")).isEqualTo("bad");
        assertThat(execution(setup.organization(), setup.execution())).as("the dead letter stays as it was")
                .containsEntry("status", "DEAD_LETTERED");
    }

    @Test
    void theReplayDoesNotJudgeTheConditionAgain() {
        Setup setup = deadLetter("quantity__c > 10");
        removeRule(setup.admin(), "NoBad__c");
        // The record no longer meets the condition; the person decided to run it anyway.
        Response changed = changeRecord(setup.admin(), setup.record(), "{\"quantity__c\":1}");
        assertThat(changed.status()).as(changed.body()).isEqualTo(200);

        String replayed = JsonPath.read(replay(setup.admin(), setup.execution()).body(), "$.data.replay");
        runWorker();

        assertThat(execution(setup.organization(), replayed)).containsEntry("status", "COMPLETED");
    }

    @Test
    void aReplayWorksOnTheRecordAsItIsNowAndEndsAsRecordGoneWhenItWasRemoved() {
        Setup setup = deadLetter(null);
        setup.admin().request("DELETE", RECORDS + "/" + setup.record(), null);
        settle(setup.organization());

        String replayed = JsonPath.read(replay(setup.admin(), setup.execution()).body(), "$.data.replay");
        runWorker();

        assertThat(execution(setup.organization(), replayed)).containsEntry("status", "SKIPPED")
                .containsEntry("reason", "record_gone");
    }

    @Test
    void aReplayStillObeysTheFeatureAndTheSwitchOfTheWorkflow() {
        Setup off = deadLetter(null);
        Setup noPlan = deadLetter(null);
        String offReplay = JsonPath.read(replay(off.admin(), off.execution()).body(), "$.data.replay");
        String noPlanReplay = JsonPath.read(replay(noPlan.admin(), noPlan.execution()).body(), "$.data.replay");
        switchTo(off.admin(), "Flow__c", false);
        loseTheFeature(noPlan.organization());

        runWorker();

        assertThat(execution(off.organization(), offReplay)).containsEntry("reason", "workflow_inactive");
        assertThat(execution(noPlan.organization(), noPlanReplay)).containsEntry("status", "SKIPPED")
                .containsEntry("reason", "feature_off");
    }

    @Test
    void aReplayThatFailsAgainIsADeadLetterOfTheNextGenerationAndCanBeReplayedInTurn() {
        Setup setup = deadLetter(null);

        String first = JsonPath.read(replay(setup.admin(), setup.execution()).body(), "$.data.replay");
        runWorker();
        assertThat(execution(setup.organization(), first)).containsEntry("status", "DEAD_LETTERED");
        String second = JsonPath.read(replay(setup.admin(), first).body(), "$.data.replay");

        assertThat(execution(setup.organization(), second)).containsEntry("replay_generation", "2")
                .containsEntry("replay_of", first);
    }

    // ---- once ----

    @Test
    void replayingTwiceMakesOneAndTheSecondAnswerNamesTheFirst() {
        Setup setup = deadLetter(null);

        Response first = replay(setup.admin(), setup.execution());
        Response second = replay(setup.admin(), setup.execution());

        assertThat(JsonPath.<String>read(first.body(), "$.data.outcome")).isEqualTo("REPLAYED");
        assertThat(JsonPath.<String>read(second.body(), "$.data.outcome")).isEqualTo("ALREADY_REPLAYED");
        assertThat(JsonPath.<String>read(second.body(), "$.data.replay"))
                .isEqualTo(JsonPath.<String>read(first.body(), "$.data.replay"));
        assertThat(scalar(setup.organization(), "select count(*) from workflow_execution where replay_of is not "
                + "null")).isEqualTo(1);
    }

    @Test
    void twoReplaysClickedAtTheSameMomentMakeOneNewExecution() throws Exception {
        Setup setup = deadLetter(null);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        List<Future<Response>> answers = new ArrayList<>();

        try {
            for (int i = 0; i < 4; i++) {
                answers.add(pool.submit(() -> replay(setup.admin(), setup.execution())));
            }
            List<String> outcomes = new ArrayList<>();
            for (Future<Response> answer : answers) {
                Response response = answer.get();
                assertThat(response.status()).as(response.body()).isEqualTo(200);
                outcomes.add(JsonPath.read(response.body(), "$.data.outcome"));
            }

            assertThat(outcomes.stream().filter("REPLAYED"::equals)).hasSize(1);
            assertThat(outcomes.stream().filter("ALREADY_REPLAYED"::equals)).hasSize(3);
        } finally {
            pool.shutdownNow();
        }
        assertThat(scalar(setup.organization(), "select count(*) from workflow_execution where replay_of is not "
                + "null")).isEqualTo(1);
    }

    @Test
    void aReplayedDeadLetterIsNoLongerCountedAsWaitingForAPerson() {
        Setup setup = deadLetter(null);
        assertThat(JsonPath.<Number>read(setup.admin().get(EXECUTIONS + "/dead-letters/summary").body(),
                "$.data.total").longValue()).isEqualTo(1);

        replay(setup.admin(), setup.execution());

        assertThat(JsonPath.<Number>read(setup.admin().get(EXECUTIONS + "/dead-letters/summary").body(),
                "$.data.total").longValue()).isZero();
        assertThat(JsonPath.<String>read(setup.admin().get(EXECUTIONS + "/" + setup.execution()).body(),
                "$.data.replayedBy")).isNotBlank();
    }

    // ---- what can be replayed, from where ----

    @Test
    void onlyADeadLetterIsReplayedAndAStepThatCompletedIsNeverRunAgain() {
        Setup setup = deadLetter(null);
        String done = anExecutionThatCompleted(setup);

        Response notDead = replay(setup.admin(), done);
        Response before = setup.admin().postJson(EXECUTIONS + "/" + setup.execution() + "/replay", "{\"fromStep\":1}");
        Response skipping = setup.admin().postJson(EXECUTIONS + "/" + setup.execution() + "/replay",
                "{\"fromStep\":3}");

        assertThat(JsonPath.<String>read(notDead.body(), "$.data.outcome")).isEqualTo("NOT_REPLAYABLE");
        assertThat(JsonPath.<String>read(notDead.body(), "$.data.reason")).isEqualTo("not_dead_lettered");
        assertThat(before.status()).as(before.body()).isEqualTo(400);
        assertThat(skipping.status()).as("a step that can never succeed can be passed over").isEqualTo(200);
        String replayed = JsonPath.read(skipping.body(), "$.data.replay");
        runWorker();
        assertThat(steps(setup.organization(), replayed)).containsExactly("Last:COMPLETED:1");
    }

    private String anExecutionThatCompleted(Setup setup) {
        workflowExists(setup.admin(), "Easy", recordTrigger("RECORD_CREATED"), null, action("Tell", "easy.event"));
        record(setup.admin(), "{\"fieldA__c\":\"other\"}");
        settle(setup.organization());
        runWorker();
        return rows(setup.organization(), "select id::text as id from workflow_execution where status = 'COMPLETED' "
                + "and workflow_api_name = 'Easy__c'").get(0).get("id");
    }

    // ---- many at once ----

    @Test
    void aBulkReplayAnswersForEachExecutionAndOneThatCannotBeReplayedDoesNotStopTheOthers() {
        Setup setup = deadLetter(null);
        String second = anotherDeadLetter(setup);
        String done = anExecutionThatCompleted(setup);
        String unknown = newUuid();

        Response answer = setup.admin().postJson(EXECUTIONS + "/replay", "{\"executions\":[\"" + setup.execution()
                + "\",\"" + done + "\",\"" + unknown + "\",\"" + second + "\"]}");

        assertThat(answer.status()).as(answer.body()).isEqualTo(200);
        assertThat(JsonPath.<List<String>>read(answer.body(), "$.data.results[*].outcome")).containsExactly("REPLAYED",
                "NOT_REPLAYABLE", "NOT_FOUND", "REPLAYED");
        assertThat(JsonPath.<List<String>>read(answer.body(), "$.data.results[*].execution")).containsExactly(
                setup.execution(), done, unknown, second);
    }

    private String anotherDeadLetter(Setup setup) {
        record(setup.admin(), "{\"fieldA__c\":\"again\"}");
        settle(setup.organization());
        runWorker();
        return rows(setup.organization(), "select id::text as id from workflow_execution where status = "
                + "'DEAD_LETTERED' and id <> cast(? as uuid) and replay_of is null", setup.execution()).get(0)
                .get("id");
    }

    @Test
    void aBulkReplayIsBoundedAndNeedsAtLeastOneExecution() {
        Setup setup = deadLetter(null);
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 201; i++) {
            many.add("\"" + newUuid() + "\"");
        }

        assertThat(setup.admin().postJson(EXECUTIONS + "/replay", "{\"executions\":[" + String.join(",", many) + "]}")
                .status()).isEqualTo(400);
        assertThat(setup.admin().postJson(EXECUTIONS + "/replay", "{\"executions\":[]}").status()).isEqualTo(400);
    }

    // ---- who may ----

    @Test
    void replayNeedsItsOwnAbilityAndTheAbilityToManageDoesNotGiveIt() {
        Setup setup = deadLetter(null);
        TestBrowser manager = as(setup.organization(), memberWith(setup.organization(), setup.admin(),
                "workflows.view", "workflows.manage"));
        TestBrowser replayer = as(setup.organization(), memberWith(setup.organization(), setup.admin(),
                "workflows.view", "workflows.replay"));

        assertThat(replay(manager, setup.execution()).status()).isEqualTo(403);
        assertThat(manager.postJson(EXECUTIONS + "/replay", "{\"executions\":[\"" + setup.execution() + "\"]}")
                .status()).isEqualTo(403);
        assertThat(replay(replayer, setup.execution()).status()).isEqualTo(200);
        assertThat(replayer.postJson(EXECUTIONS + "/" + setup.execution() + "/cancel", "{}").status())
                .as("replaying is not managing").isEqualTo(403);
    }

    @Test
    void anotherOrganizationNeitherSeesNorReplaysNorCancelsThisOnesExecutions() throws Exception {
        Setup mine = deadLetter(null);
        Organization theirs = organization();
        TestBrowser other = adminWithData(theirs);

        assertThat(other.get(EXECUTIONS + "/" + mine.execution()).status()).isEqualTo(404);
        assertThat(replay(other, mine.execution()).status()).isEqualTo(404);
        assertThat(JsonPath.<List<String>>read(other.postJson(EXECUTIONS + "/replay", "{\"executions\":[\""
                + mine.execution() + "\"]}").body(), "$.data.results[*].outcome")).containsExactly("NOT_FOUND");
        assertThat(other.postJson(EXECUTIONS + "/" + mine.execution() + "/cancel", "{}").status()).isEqualTo(404);
        assertThat(JsonPath.<Number>read(other.get(EXECUTIONS + "/dead-letters/summary").body(), "$.data.total")
                .longValue()).isZero();
        assertThat(scalar(mine.organization(), "select count(*) from workflow_execution where replay_of is not null"))
                .isZero();
        long seenByThem = TenantFixtures.asTenant(theirs.id(), connection -> TenantFixtures.count(connection,
                "select count(*) from workflow_execution where status = 'DEAD_LETTERED'"));
        assertThat(seenByThem).as("row level security shows them none of the dead letters").isZero();
    }

    // ---- cancel ----

    @Test
    void anExecutionThatHasNotStartedCanBeCancelledAndOneThatFinishedCannot() {
        Organization organization = organization();
        TestBrowser admin = adminWithData(organization);
        workflowExists(admin, "Flow", recordTrigger("RECORD_CREATED"), null, action("Tell", "first.event"));
        record(admin, "{\"fieldA__c\":\"hello\"}");
        record(admin, "{\"fieldA__c\":\"there\"}");
        settle(organization);
        List<String> ids = rows(organization, "select id::text as id from workflow_execution order by id").stream()
                .map(row -> row.get("id")).toList();

        Response cancelled = admin.postJson(EXECUTIONS + "/" + ids.get(0) + "/cancel", "{}");
        runWorker();
        Response late = admin.postJson(EXECUTIONS + "/" + ids.get(1) + "/cancel", "{}");

        assertThat(JsonPath.<String>read(cancelled.body(), "$.data.status")).isEqualTo("CANCELLED");
        assertThat(execution(organization, ids.get(0))).containsEntry("reason", "cancelled_by_person");
        assertThat(execution(organization, ids.get(1))).containsEntry("status", "COMPLETED");
        assertThat(late.status()).isEqualTo(409);
        assertThat(admin.postJson(EXECUTIONS + "/" + newUuid() + "/cancel", "{}").status()).isEqualTo(404);
    }

    // ---- audit ----

    @Test
    void everyReplayAndCancelIsAuditedByIdentifiersAndOutcomeOnly() {
        Setup setup = deadLetter(null);

        String replayed = JsonPath.read(replay(setup.admin(), setup.execution()).body(), "$.data.replay");

        List<String> audits = owner("select attributes::text from audit_record where event_type = "
                + "'workflow.execution.replayed' and attributes ->> 'execution' = ?", setup.execution());
        assertThat(audits).singleElement().satisfies(audit -> assertThat(audit).contains(replayed)
                .contains("REPLAYED").doesNotContain("hello").doesNotContain("bad"));
    }
}
