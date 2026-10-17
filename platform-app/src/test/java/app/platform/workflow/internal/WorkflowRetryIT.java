package app.platform.workflow.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations.Organization;
import com.jayway.jsonpath.JsonPath;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.test.context.TestPropertySource;

/**
 * Retries, backoff and dead letters (ADR-0141, ADR-0142, ADR-0143), with failures planned by the test
 * ({@link FailureInjection}): a transient failure waits for another attempt with a growing delay and then succeeds, a
 * failed attempt leaves nothing behind, a permanent failure is not retried, the attempts are capped by the lower of the
 * definition's and the platform's number, a worker that died is a claim and not an attempt, a waiting execution holds
 * no lease, and attempts spent give a dead letter that can be cancelled while it waits.
 */
@PlatformIntegrationTest
@Import(FailureInjection.class)
@TestPropertySource(properties = {
    "platform.outbox.enabled=true",
    "platform.outbox.poll-interval=50ms",
    "platform.workflow.enabled=false"})
class WorkflowRetryIT extends ActionsSupport {

    @Autowired
    private MeterRegistry meters;

    @BeforeEach
    void noFailuresPlanned() {
        FailureInjection.reset();
    }

    private record Setup(Organization organization, TestBrowser admin, String record) {
    }

    private Setup setupWith(String action) {
        Organization organization = organization();
        TestBrowser admin = adminWithData(organization);
        workflowExists(admin, "Mark", recordTrigger("RECORD_CREATED"), null, action);
        String id = record(admin, "{\"fieldA__c\":\"hello\"}");
        settle(organization);
        return new Setup(organization, admin, id);
    }

    private static CannotAcquireLockException aLock() {
        return new CannotAcquireLockException("a lock that is not available");
    }

    // ---- a transient failure is retried with a growing wait ----

    @Test
    void aTransientFailureWaitsWithAGrowingDelayAndThenSucceeds() {
        Setup setup = setupWith(update("Set", value("fieldB__c", "done")));
        FailureInjection.fail("UPDATE_RECORD", 2, WorkflowRetryIT::aLock);

        runWorker();
        String execution = theExecution(setup.organization());
        double first = secondsUntilDue(setup.organization(), execution);
        assertThat(execution(setup.organization(), execution)).containsEntry("status", "QUEUED")
                .containsEntry("reason", "retry_scheduled").containsEntry("due", "later");
        assertThat(steps(setup.organization(), execution)).containsExactly("Set:RETRYING:1");

        spendTheWait(setup.organization(), execution);
        runWorker();
        double second = secondsUntilDue(setup.organization(), execution);
        assertThat(steps(setup.organization(), execution)).containsExactly("Set:RETRYING:2");

        spendTheWait(setup.organization(), execution);
        runWorker();

        // 30 seconds and up to a fifth more, then twice that (ADR-0142).
        assertThat(first).isBetween(25.0, 36.5);
        assertThat(second).isBetween(55.0, 73.0).isGreaterThan(first);
        assertThat(execution(setup.organization(), execution)).containsEntry("status", "COMPLETED");
        assertThat(steps(setup.organization(), execution)).containsExactly("Set:COMPLETED:3");
        assertThat(fieldOf(setup.admin(), setup.record(), "fieldB__c")).isEqualTo("done");
        assertThat(FailureInjection.calls("UPDATE_RECORD")).isEqualTo(3);
    }

    @Test
    void aFailedAttemptLeavesNothingBehindAndTheSuccessfulOneChangesTheRecordOnce() {
        Setup setup = setupWith(update("Set", value("fieldB__c", "done")));
        long before = versionOfRecord(setup.admin(), setup.record());
        FailureInjection.failAfterTheEffect("UPDATE_RECORD", 1, WorkflowRetryIT::aLock);

        runWorker();
        settle(setup.organization());

        assertThat(setup.admin().get(RECORDS + "/" + setup.record()).body()).as("the work was rolled back")
                .doesNotContain("done");
        assertThat(versionOfRecord(setup.admin(), setup.record())).isEqualTo(before);
        assertThat(owner("select count(*) from outbox_event where event_type = 'record.updated' and payload ->> "
                + "'recordId' = ?", setup.record())).containsExactly("0");

        spendTheWait(setup.organization(), theExecution(setup.organization()));
        runWorker();
        settle(setup.organization());

        assertThat(fieldOf(setup.admin(), setup.record(), "fieldB__c")).isEqualTo("done");
        assertThat(versionOfRecord(setup.admin(), setup.record())).isEqualTo(before + 1);
        assertThat(owner("select count(*) from outbox_event where event_type = 'record.updated' and payload ->> "
                + "'recordId' = ?", setup.record())).containsExactly("1");
    }

    @Test
    void aWaitingExecutionHoldsNoLeaseAndAReportedFailureDoesNotCountAsACrash() {
        Setup setup = setupWith(update("Set", value("fieldB__c", "done")));
        FailureInjection.fail("UPDATE_RECORD", 1, WorkflowRetryIT::aLock);

        runWorker();

        String execution = theExecution(setup.organization());
        assertThat(execution(setup.organization(), execution)).containsEntry("lease", "free")
                .containsEntry("attempts", "0");
        assertThat(rows(setup.organization(), "select finished_at is null as open from workflow_step").get(0))
                .containsEntry("open", "t");
    }

    @Test
    void nothingIsClaimedWhileTheWaitLasts() {
        Setup setup = setupWith(update("Set", value("fieldB__c", "done")));
        FailureInjection.fail("UPDATE_RECORD", 1, WorkflowRetryIT::aLock);
        runWorker();

        int claimed = dispatcher.pollExecutionsOnce();

        assertThat(claimed).isZero();
        assertThat(FailureInjection.calls("UPDATE_RECORD")).isEqualTo(1);
    }

    // ---- a permanent failure is not retried ----

    @Test
    void aFailureNobodyUnderstoodIsPermanentAndGoesStraightToTheDeadLetters() {
        Setup setup = setupWith(update("Set", value("fieldB__c", "done")));
        FailureInjection.fail("UPDATE_RECORD", 1, () -> new IllegalStateException("a message with SECRET-TEXT in it"));

        runWorker();

        String execution = theExecution(setup.organization());
        assertThat(execution(setup.organization(), execution)).containsEntry("status", "DEAD_LETTERED")
                .containsEntry("reason", "action_failed").containsEntry("failure_type", "IllegalStateException");
        assertThat(steps(setup.organization(), execution)).containsExactly("Set:FAILED:1");
        assertThat(rows(setup.organization(), "select * from workflow_step").toString()).doesNotContain("SECRET-TEXT");
        assertThat(FailureInjection.calls("UPDATE_RECORD")).isEqualTo(1);
    }

    // ---- how many attempts ----

    @Test
    void theAttemptsAreTheLowerOfTheDefinitionsAndThePlatformsNumberAndTheLastOneIsADeadLetter() {
        Setup setup = setupWith(updateWithAttempts("Set", 2, value("fieldB__c", "done")));
        FailureInjection.fail("UPDATE_RECORD", 10, WorkflowRetryIT::aLock);

        runWorker();
        String execution = theExecution(setup.organization());
        spendTheWait(setup.organization(), execution);
        runWorker();

        assertThat(execution(setup.organization(), execution)).containsEntry("status", "DEAD_LETTERED")
                .containsEntry("reason", "retries_exhausted");
        assertThat(steps(setup.organization(), execution)).containsExactly("Set:FAILED:2");
        assertThat(FailureInjection.calls("UPDATE_RECORD")).as("two attempts, as the definition lowered it")
                .isEqualTo(2);
    }

    @Test
    void withoutAnyLimitOfItsOwnAnActionGetsThePlatformsFiveAttempts() {
        Setup setup = setupWith(update("Set", value("fieldB__c", "done")));
        FailureInjection.fail("UPDATE_RECORD", 10, WorkflowRetryIT::aLock);
        String execution = null;

        for (int attempt = 1; attempt <= 5; attempt++) {
            runWorker();
            execution = theExecution(setup.organization());
            if (attempt < 5) {
                assertThat(execution(setup.organization(), execution)).containsEntry("status", "QUEUED");
                spendTheWait(setup.organization(), execution);
            }
        }

        assertThat(execution(setup.organization(), execution)).containsEntry("status", "DEAD_LETTERED")
                .containsEntry("reason", "retries_exhausted");
        assertThat(FailureInjection.calls("UPDATE_RECORD")).isEqualTo(5);
    }

    @Test
    void aDefinitionCannotRaiseTheAttemptsAboveThePlatformsNumber() {
        Organization organization = organization();
        TestBrowser admin = adminWithData(organization);

        Response refused = createWorkflow(admin, "Greedy", recordTrigger("RECORD_CREATED"), null,
                updateWithAttempts("Set", 50, value("fieldB__c", "done")));

        assertThat(refused.status()).as(refused.body()).isEqualTo(400);
        assertThat(refused.body()).contains("maxAttempts");
    }

    // ---- a crash is a claim, not an attempt ----

    @Test
    void aWorkerThatDiedMidAttemptIsAClaimAndNotAFailedAttempt() {
        Setup setup = setupWith(update("Set", value("fieldB__c", "done")));
        // The instance died while it ran the step: the claim is counted, the lease is expired, nothing committed.
        run(setup.organization(), "update workflow_execution set status = 'RUNNING', attempts = 1, "
                + "started_at = now() - interval '3 minutes', locked_until = now() - interval '1 minute', "
                + "version = version + 1");

        runWorker();

        String execution = theExecution(setup.organization());
        assertThat(execution(setup.organization(), execution)).containsEntry("status", "COMPLETED")
                .containsEntry("attempts", "2");
        assertThat(steps(setup.organization(), execution)).as("the step was attempted once").containsExactly(
                "Set:COMPLETED:1");
    }

    // ---- dead letters ----

    @Test
    void theDeadLettersAreCountedByAGaugeAndSummarisedByWorkflowAndReason() {
        Setup setup = setupWith(update("Set", value("fieldB__c", "done")));
        FailureInjection.fail("UPDATE_RECORD", 1, () -> new IllegalStateException("x"));
        runWorker();

        dispatcher.sampleQueue();
        Response summary = setup.admin().get(EXECUTIONS + "/dead-letters/summary");
        Response listed = setup.admin().get(EXECUTIONS + "?dead=true");

        assertThat(meters.get("platform.workflow.dead_letters").gauge().value()).isGreaterThanOrEqualTo(1.0);
        assertThat(JsonPath.<Number>read(summary.body(), "$.data.total").longValue()).isEqualTo(1);
        assertThat(JsonPath.<String>read(summary.body(), "$.data.groups[0].workflow")).isEqualTo("Mark__c");
        assertThat(JsonPath.<String>read(summary.body(), "$.data.groups[0].reason")).isEqualTo("action_failed");
        assertThat(JsonPath.<Integer>read(listed.body(), "$.data.length()")).isEqualTo(1);
        assertThat(JsonPath.<String>read(listed.body(), "$.data[0].status")).isEqualTo("DEAD_LETTERED");
        assertThat(setup.admin().get(EXECUTIONS + "?dead=true&status=QUEUED").status()).isEqualTo(400);
    }

    @Test
    void aWaitingExecutionCanBeCancelledAndItsStepWillNotBeAttemptedAgain() {
        Setup setup = setupWith(update("Set", value("fieldB__c", "done")));
        FailureInjection.fail("UPDATE_RECORD", 1, WorkflowRetryIT::aLock);
        runWorker();
        String execution = theExecution(setup.organization());

        Response cancelled = setup.admin().postJson(EXECUTIONS + "/" + execution + "/cancel", "{}");

        assertThat(cancelled.status()).as(cancelled.body()).isEqualTo(200);
        assertThat(JsonPath.<String>read(cancelled.body(), "$.data.status")).isEqualTo("CANCELLED");
        assertThat(JsonPath.<String>read(cancelled.body(), "$.data.reason")).isEqualTo("cancelled_by_person");
        assertThat(steps(setup.organization(), execution)).containsExactly("Set:FAILED:1");
        spendTheWait(setup.organization(), execution);
        runWorker();
        assertThat(FailureInjection.calls("UPDATE_RECORD")).isEqualTo(1);
        assertThat(setup.admin().postJson(EXECUTIONS + "/" + execution + "/cancel", "{}").status())
                .as("a second cancel finds nothing to cancel").isEqualTo(409);
    }

    @Test
    void theMetricsCountTheAttemptsTheRetriesAndTheDeadLetterWithoutAValueInATag() {
        Setup setup = setupWith(update("Set", value("fieldB__c", "done")));
        FailureInjection.fail("UPDATE_RECORD", 5, WorkflowRetryIT::aLock);
        for (int attempt = 1; attempt <= 5; attempt++) {
            runWorker();
            spendTheWait(setup.organization(), theExecution(setup.organization()));
        }

        assertThat(meters.get("platform.workflow.retries").counter().count()).isGreaterThanOrEqualTo(4.0);
        assertThat(meters.get("platform.workflow.attempts").tag("action", "UPDATE_RECORD").tag("result", "retry")
                .counter().count()).isGreaterThanOrEqualTo(4.0);
        assertThat(meters.get("platform.workflow.attempts").tag("action", "UPDATE_RECORD")
                .tag("result", "dead_lettered").counter().count()).isGreaterThanOrEqualTo(1.0);
        assertThat(meters.get("platform.workflow.retry.delay").summary().count()).isGreaterThanOrEqualTo(4);
    }
}
