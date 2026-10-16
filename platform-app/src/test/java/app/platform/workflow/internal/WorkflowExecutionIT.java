package app.platform.workflow.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.sharedkernel.ActorId;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestApplication;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations.Organization;
import app.platform.testsupport.TestPlatform;
import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * The worker that runs executions (ADR-0137), driven by the test so that every state between "queued" and "ended" can
 * be looked at: the condition judged on the record as it is (true, false and not evaluable), a record deleted before
 * the run, the version an execution was created under, a workflow switched off or removed or an organization that
 * lost the feature, a failing step, a crash in the middle of an execution, a run claimed too often, two instances
 * working at once, the history the API shows and the clean-up.
 */
@PlatformIntegrationTest
@TestPropertySource(properties = {
    "platform.outbox.enabled=true",
    "platform.outbox.poll-interval=50ms",
    "platform.workflow.enabled=false"})
class WorkflowExecutionIT extends WorkflowSupport {

    private static TestApplication instanceB;

    @BeforeAll
    static void startTheOtherInstance() {
        instanceB = TestApplication.start("platform.outbox.enabled=true", "platform.outbox.poll-interval=50ms",
                "platform.workflow.enabled=false");
    }

    @AfterAll
    static void stopTheOtherInstance() {
        instanceB.close();
    }

    /** An organization with its administrator and one workflow on creation that announces {@code done.event}. */
    private record Setup(Organization organization, TestBrowser admin) {
    }

    private Setup withOnCreate(String condition, String... actions) {
        Organization organization = organization();
        TestBrowser admin = adminWithData(organization);
        workflowExists(admin, "OnCreate", recordTrigger("RECORD_CREATED"), condition,
                actions.length == 0 ? new String[] {action("Tell", "done.event")} : actions);
        return new Setup(organization, admin);
    }

    // ---- the happy path ----

    @Test
    void aQueuedExecutionRunsItsStepsAndCompletesAndTheEventItAnnouncesIsDeliveredOnce() {
        Setup setup = withOnCreate(null, action("First", "step.one"), action("Second", "step.two"));
        String record = record(setup.admin(), "{\"fieldA__c\":\"x\"}");
        settle(setup.organization());

        runWorker();
        settle(setup.organization());

        List<Map<String, String>> found = executions(setup.organization());
        assertThat(found).hasSize(1);
        assertThat(found.get(0)).containsEntry("status", "COMPLETED").containsEntry("attempts", "1")
                .containsEntry("record_id", record).containsEntry("failure_type", null);
        assertThat(rows(setup.organization(), "select position, name, status from workflow_step order by position"))
                .extracting(row -> row.get("name") + ":" + row.get("status"))
                .containsExactly("First:COMPLETED", "Second:COMPLETED");
        assertThat(signals(setup.organization(), "workflow.signal.step.one")).isEqualTo(1);
        assertThat(signals(setup.organization(), "workflow.signal.step.two")).isEqualTo(1);
        assertThat(scalar(setup.organization(), "select count(*) from outbox_event where event_type like "
                + "'workflow.signal.%' and status = 'DELIVERED'")).as("the relay delivered them").isEqualTo(2);
        runWorker();
        assertThat(signals(setup.organization(), "workflow.signal.step.one")).as("nothing runs twice").isEqualTo(1);
    }

    // ---- the condition ----

    @Test
    void theConditionIsJudgedOnTheRecordTrueFalseAndNotEvaluable() {
        Setup setup = withOnCreate("10 / quantity__c > 1", action("Tell", "done.event"));
        record(setup.admin(), "{\"quantity__c\":2}");
        record(setup.admin(), "{\"quantity__c\":20}");
        record(setup.admin(), "{\"quantity__c\":0}");
        settle(setup.organization());

        runWorker();

        Map<String, String> outcomeByQuantity = new java.util.TreeMap<>();
        rows(setup.organization(), "select r.payload ->> 'quantity__c' as quantity, e.status, e.reason "
                + "from workflow_execution e join object_record r on r.id = e.record_id")
                .forEach(row -> outcomeByQuantity.put(row.get("quantity"),
                        row.get("status") + "/" + row.get("reason")));
        // 10 / 2 is 5, more than 1; 10 / 20 is a half, not more than 1; 10 / 0 cannot be evaluated.
        assertThat(outcomeByQuantity).containsExactly(Map.entry("0", "SKIPPED/condition_not_evaluable"),
                Map.entry("2", "COMPLETED/null"), Map.entry("20", "SKIPPED/condition_false"));
        assertThat(signals(setup.organization(), "workflow.signal.done.event")).isEqualTo(1);
    }

    @Test
    void theRecordIsReadWhenTheExecutionRunsNotAsItWasWhenTheEventWasMade() {
        Setup setup = withOnCreate("quantity__c > 10", action("Tell", "done.event"));
        String record = record(setup.admin(), "{\"quantity__c\":5}");
        settle(setup.organization());
        changeRecord(setup.admin(), record, "{\"quantity__c\":50}");
        settle(setup.organization());

        runWorker();

        assertThat(statuses(setup.organization())).as("created at 5, run at 50: the condition sees 50")
                .containsExactly("COMPLETED");
    }

    // ---- the record ----

    @Test
    void aRecordDeletedBeforeTheRunIsSkippedAndARecordInTheBinIsReadByTheDeleteTrigger() {
        Setup setup = withOnCreate(null);
        workflowExists(setup.admin(), "OnDelete", recordTrigger("RECORD_DELETED"), null, action("Gone", "gone.event"));
        String first = record(setup.admin(), "{\"fieldA__c\":\"a\"}");
        settle(setup.organization());
        setup.admin().request("DELETE", RECORDS + "/" + first, null);
        settle(setup.organization());

        runWorker();

        assertThat(executions(setup.organization()).stream()
                .map(row -> row.get("workflow_api_name") + ":" + row.get("status") + ":" + row.get("reason")))
                .containsExactlyInAnyOrder("OnCreate__c:SKIPPED:record_gone", "OnDelete__c:COMPLETED:null");
        assertThat(signals(setup.organization(), "workflow.signal.done.event")).isZero();
        assertThat(signals(setup.organization(), "workflow.signal.gone.event")).isEqualTo(1);
    }

    @Test
    void aRecordRemovedForGoodBeforeTheRunIsSkippedEvenForTheDeleteTrigger() {
        Setup setup = withOnCreate(null);
        workflowExists(setup.admin(), "OnDelete", recordTrigger("RECORD_DELETED"), null, action("Gone", "gone.event"));
        String record = record(setup.admin(), "{\"fieldA__c\":\"a\"}");
        setup.admin().request("DELETE", RECORDS + "/" + record, null);
        setup.admin().request("DELETE", "/api/v1/objects/" + OBJECT + "/recycle-bin/" + record, null);
        settle(setup.organization());

        runWorker();

        assertThat(reasons(setup.organization())).containsOnly("record_gone");
    }

    // ---- the version ----

    @Test
    void anExecutionRunsTheVersionItWasCreatedUnderWhateverWasPublishedSince() {
        Setup setup = withOnCreate(null, action("Tell", "first.event"));
        record(setup.admin(), "{\"fieldA__c\":\"before\"}");
        settle(setup.organization());

        Response changed = change(setup.admin(), "OnCreate__c", recordTrigger("RECORD_CREATED"), null, true,
                action("Tell", "second.event"));
        assertThat(changed.status()).as(changed.body()).isEqualTo(200);
        assertThat(JsonPath.<Integer>read(changed.body(), "$.data.definitionVersion")).isEqualTo(2);
        record(setup.admin(), "{\"fieldA__c\":\"after\"}");
        settle(setup.organization());

        runWorker();

        assertThat(executions(setup.organization()).stream()
                .map(row -> row.get("workflow_version_number") + ":" + row.get("status")))
                .containsExactly("1:COMPLETED", "2:COMPLETED");
        assertThat(signals(setup.organization(), "workflow.signal.first.event")).isEqualTo(1);
        assertThat(signals(setup.organization(), "workflow.signal.second.event")).isEqualTo(1);
    }

    // ---- the workflow and the plan ----

    @Test
    void aSwitchedOffOrRemovedWorkflowStopsItsQueuedExecutionsAndAnOrganizationWithoutTheFeatureSkipsThem() {
        Setup off = withOnCreate(null);
        Setup removed = withOnCreate(null);
        Setup noPlan = withOnCreate(null);
        for (Setup setup : List.of(off, removed, noPlan)) {
            record(setup.admin(), "{\"fieldA__c\":\"x\"}");
            settle(setup.organization());
        }

        switchTo(off.admin(), "OnCreate__c", false);
        removed.admin().request("DELETE", WORKFLOWS + "/" + OBJECT + "/OnCreate__c", null);
        TestPlatform.subscribe(plans, subscriptions, noPlan.organization().id(), 5, 3);
        runWorker();

        assertThat(statuses(off.organization())).containsExactly("SKIPPED");
        assertThat(reasons(off.organization())).containsExactly("workflow_inactive");
        assertThat(statuses(removed.organization())).containsExactly("CANCELLED");
        assertThat(reasons(removed.organization())).containsExactly("workflow_removed");
        assertThat(reasons(noPlan.organization())).containsExactly("feature_off");
        assertThat(signals(off.organization(), "workflow.signal.done.event")).isZero();
    }

    // ---- a failing step ----

    @Test
    void aStepThatFailsEndsTheExecutionAsFailedWithTheTypeNameAndNeverTheMessage() {
        Setup setup = withOnCreate(null);
        // A definition of a later release: a type of action this release does not carry out.
        run(setup.organization(), "insert into workflow_version (workflow_id, version_number, trigger_kind, trigger, "
                + "actions, created_by, updated_by) select id, 2, 'RECORD_CREATED', "
                + "cast('{\"kind\":\"RECORD_CREATED\"}' as jsonb), cast('[{\"type\":\"RUN_SCRIPT\","
                + "\"name\":\"Script\",\"settings\":{}}]' as jsonb), cast(? as uuid), cast(? as uuid) "
                + "from workflow where api_name = 'OnCreate__c'",
                ActorId.SYSTEM.value(), ActorId.SYSTEM.value());
        run(setup.organization(), "update workflow set definition_version = 2, version = version + 1 "
                + "where api_name = 'OnCreate__c'");
        record(setup.admin(), "{\"fieldA__c\":\"x\"}");
        settle(setup.organization());

        runWorker();

        assertThat(executions(setup.organization())).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("status", "FAILED").containsEntry("reason", "action_failed")
                    .containsEntry("failure_type", "UnknownActionType").containsEntry("workflow_version_number", "2");
        });
        assertThat(rows(setup.organization(), "select name, status, failure_type from workflow_step"))
                .singleElement().satisfies(row -> assertThat(row).containsEntry("status", "FAILED")
                        .containsEntry("failure_type", "UnknownActionType"));
    }

    // ---- crashes ----

    @Test
    void aCrashInTheMiddleOfAnExecutionIsRecoveredWithoutRepeatingAStepThatCompleted() {
        Setup setup = withOnCreate(null, action("First", "step.one"), action("Second", "step.two"));
        record(setup.admin(), "{\"fieldA__c\":\"x\"}");
        settle(setup.organization());
        // The instance that ran the first step died before the second: the step is committed, the claim is expired.
        run(setup.organization(), "insert into workflow_step (execution_id, position, name, action_type, status, "
                + "finished_at, created_by, updated_by) select id, 1, 'First', 'PUBLISH_EVENT', 'COMPLETED', now(), "
                + "cast(? as uuid), cast(? as uuid) "
                + "from workflow_execution", ActorId.SYSTEM.value(), ActorId.SYSTEM.value());
        run(setup.organization(), "update workflow_execution set status = 'RUNNING', attempts = 1, "
                + "started_at = now() - interval '2 minutes', locked_until = now() - interval '1 minute', "
                + "version = version + 1");

        runWorker();
        settle(setup.organization());

        assertThat(executions(setup.organization())).singleElement().satisfies(row ->
                assertThat(row).containsEntry("status", "COMPLETED").containsEntry("attempts", "2"));
        assertThat(signals(setup.organization(), "workflow.signal.step.one")).as("not repeated").isZero();
        assertThat(signals(setup.organization(), "workflow.signal.step.two")).isEqualTo(1);
    }

    @Test
    void anExecutionClaimedMoreOftenThanTheLimitIsSetAsideWithoutBeingRunAgain() {
        Setup setup = withOnCreate(null);
        record(setup.admin(), "{\"fieldA__c\":\"x\"}");
        settle(setup.organization());
        run(setup.organization(), "update workflow_execution set status = 'RUNNING', attempts = 3, "
                + "locked_until = now() - interval '1 minute', version = version + 1");

        runWorker();

        assertThat(executions(setup.organization())).singleElement().satisfies(row ->
                assertThat(row).containsEntry("status", "FAILED").containsEntry("reason", "attempts_exhausted")
                        .containsEntry("failure_type", "AttemptsExhausted"));
        assertThat(signals(setup.organization(), "workflow.signal.done.event")).isZero();
    }

    @Test
    void anExecutionWhoseLeaseHasNotExpiredIsLeftToTheInstanceThatHoldsIt() {
        Setup setup = withOnCreate(null);
        record(setup.admin(), "{\"fieldA__c\":\"x\"}");
        settle(setup.organization());
        run(setup.organization(), "update workflow_execution set status = 'RUNNING', attempts = 1, "
                + "locked_until = now() + interval '10 minutes', version = version + 1");

        runWorker();

        assertThat(statuses(setup.organization())).containsExactly("RUNNING");
    }

    // ---- two instances ----

    @Test
    void twoInstancesWorkingAtOnceNeverRunOneExecutionTwice() throws Exception {
        Setup setup = withOnCreate(null);
        for (int i = 0; i < 30; i++) {
            record(setup.admin(), "{\"fieldA__c\":\"r" + i + "\"}");
        }
        Awaitility.await().atMost(java.time.Duration.ofSeconds(60))
                .until(() -> executions(setup.organization()).size() >= 30);
        settle(setup.organization());
        Dispatcher other = instanceB.bean(Dispatcher.class);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        try {
            List<Future<?>> work = new ArrayList<>();
            work.add(pool.submit(() -> runWorker(dispatcher)));
            work.add(pool.submit(() -> runWorker(other)));
            for (Future<?> future : work) {
                future.get();
            }
        } finally {
            pool.shutdownNow();
        }
        settle(setup.organization());

        List<Map<String, String>> found = executions(setup.organization());
        assertThat(found).hasSize(30).allSatisfy(row -> assertThat(row).containsEntry("status", "COMPLETED")
                .containsEntry("attempts", "1"));
        assertThat(signals(setup.organization(), "workflow.signal.done.event")).isEqualTo(30);
    }

    // ---- the history the API shows ----

    @Test
    void theHistoryIsPagedFilteredAndShowsTheStepsOfOneExecution() {
        Setup setup = withOnCreate(null, action("First", "step.one"), action("Second", "step.two"));
        for (int i = 0; i < 3; i++) {
            record(setup.admin(), "{\"fieldA__c\":\"r" + i + "\"}");
        }
        settle(setup.organization());
        runWorker();

        Response firstPage = setup.admin().get(EXECUTIONS + "?limit=2");
        String cursor = JsonPath.read(firstPage.body(), "$.pagination.nextCursor");
        Response secondPage = setup.admin().get(EXECUTIONS + "?limit=2&cursor=" + cursor);
        Response filtered = setup.admin().get(EXECUTIONS + "?status=FAILED");
        Response byWorkflow = setup.admin().get(EXECUTIONS + "?workflow=OnCreate__c&status=COMPLETED");
        String id = JsonPath.read(firstPage.body(), "$.data[0].id");
        Response one = setup.admin().get(EXECUTIONS + "/" + id);

        assertThat(firstPage.status()).as(firstPage.body()).isEqualTo(200);
        assertThat(JsonPath.<List<String>>read(firstPage.body(), "$.data[*].id")).hasSize(2);
        assertThat(JsonPath.<Boolean>read(firstPage.body(), "$.pagination.hasMore")).isTrue();
        assertThat(JsonPath.<List<String>>read(secondPage.body(), "$.data[*].id")).hasSize(1)
                .doesNotContainAnyElementsOf(JsonPath.<List<String>>read(firstPage.body(), "$.data[*].id"));
        assertThat(JsonPath.<List<String>>read(filtered.body(), "$.data[*].id")).isEmpty();
        assertThat(JsonPath.<List<String>>read(byWorkflow.body(), "$.data[*].id")).hasSize(3);
        assertThat(JsonPath.<List<String>>read(one.body(), "$.data.steps[*].name")).containsExactly("First", "Second");
        assertThat(JsonPath.<String>read(one.body(), "$.data.status")).isEqualTo("COMPLETED");
        assertThat(JsonPath.<String>read(one.body(), "$.data.workflow")).isEqualTo("OnCreate__c");
        assertThat(setup.admin().get(EXECUTIONS + "?status=NOPE").status()).isEqualTo(400);
        assertThat(setup.admin().get(EXECUTIONS + "?cursor=garbage").status()).isEqualTo(400);
        assertThat(setup.admin().get(EXECUTIONS + "/" + java.util.UUID.randomUUID()).status()).isEqualTo(404);
    }

    // ---- retention ----

    @Test
    void finishedExecutionsAreRemovedAfterTheRetentionWithTheirStepsAndOneAuditRecordCountsThem() {
        Setup setup = withOnCreate(null);
        record(setup.admin(), "{\"fieldA__c\":\"old\"}");
        record(setup.admin(), "{\"fieldA__c\":\"recent\"}");
        settle(setup.organization());
        runWorker();
        run(setup.organization(), "update workflow_execution set finished_at = now() - interval '100 days', "
                + "version = version + 1 where record_id = (select id from object_record "
                + "where payload ->> 'fieldA__c' = 'old')");
        run(setup.organization(), "update workflow_execution set finished_at = now() - interval '10 days', "
                + "version = version + 1 where record_id = (select id from object_record "
                + "where payload ->> 'fieldA__c' = 'recent')");

        int removed = dispatcher.purgeOnce();

        assertThat(removed).isGreaterThanOrEqualTo(1);
        assertThat(executions(setup.organization())).hasSize(1);
        assertThat(scalar(setup.organization(), "select count(*) from workflow_step")).as("the steps went with it")
                .isEqualTo(1);
        assertThat(scalar(setup.organization(), "select count(*) from audit_record where event_type = "
                + "'retention.workflow.cleaned'")).isEqualTo(1);
    }
}
