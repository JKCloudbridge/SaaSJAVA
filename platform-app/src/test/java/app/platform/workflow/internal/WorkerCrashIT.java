package app.platform.workflow.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestApplication;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestOrganizations.Organization;
import java.time.Duration;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/**
 * The chaos test of the worker (ADR-0165): the process dies in the middle of an action, in a place chosen by the test
 * and not by luck. The death is an {@link Error} that nothing in the runtime catches, thrown by the action itself,
 * before its work or after it, so the transaction of the step never commits. What the platform promises after that is
 * proved against the real database with a second, real instance: the claim holds until its lease ends, nothing the dead
 * attempt did is left behind, and when the lease has ended another instance finishes the execution with the effect made
 * exactly once, whatever kind of effect it is (a record, a notice).
 */
@PlatformIntegrationTest
@Import(FailureInjection.class)
@TestPropertySource(properties = {
    "platform.outbox.enabled=true",
    "platform.outbox.poll-interval=50ms",
    "platform.workflow.enabled=false"})
class WorkerCrashIT extends ActionsSupport {

    private static TestApplication survivor;

    @BeforeAll
    static void startTheOtherInstance() {
        survivor = TestApplication.start("platform.outbox.enabled=true", "platform.outbox.poll-interval=50ms",
                "platform.workflow.enabled=false");
    }

    @AfterAll
    static void stopTheOtherInstance() {
        survivor.close();
    }

    @BeforeEach
    void noFailuresPlanned() {
        FailureInjection.reset();
    }

    private record Setup(Organization organization, TestBrowser admin, String record, String execution) {
    }

    private Setup setupWith(String action) {
        Organization organization = organization();
        TestBrowser admin = adminWithData(organization);
        workflowExists(admin, "Mark", recordTrigger("RECORD_CREATED"), null, action);
        String id = record(admin, "{\"fieldA__c\":\"hello\"}");
        settle(organization);
        return new Setup(organization, admin, id, theExecution(organization));
    }

    private static void theLeaseEnds(Organization organization) {
        run(organization, "update workflow_execution set locked_until = now() - interval '1 minute', "
                + "version = version + 1 where status = 'RUNNING'");
    }

    private static int survivorLooks() {
        Dispatcher dispatcher = survivor.bean(Dispatcher.class);
        int claimed = dispatcher.pollExecutionsOnce();
        return claimed;
    }

    @Test
    void aWorkerThatDiesAfterTheEffectLeavesNothingAndTheSurvivorMakesTheEffectOnce() {
        Setup setup = setupWith(update("Set", value("fieldB__c", "done")));
        long before = versionOfRecord(setup.admin(), setup.record());
        FailureInjection.crash("UPDATE_RECORD", 1, true);

        assertThatThrownBy(this::runWorker).isInstanceOf(FailureInjection.SimulatedCrash.class);

        assertThat(setup.admin().get(RECORDS + "/" + setup.record()).body()).as("the dead attempt left nothing")
                .doesNotContain("done");
        assertThat(versionOfRecord(setup.admin(), setup.record())).isEqualTo(before);
        assertThat(execution(setup.organization(), setup.execution())).containsEntry("status", "RUNNING")
                .containsEntry("lease", "held");
        assertThat(survivorLooks()).as("the claim holds until its lease ends").isZero();

        theLeaseEnds(setup.organization());
        assertThat(survivorLooks()).isEqualTo(1);
        settle(setup.organization());

        assertThat(fieldOf(setup.admin(), setup.record(), "fieldB__c")).isEqualTo("done");
        assertThat(versionOfRecord(setup.admin(), setup.record())).as("the effect, once").isEqualTo(before + 1);
        assertThat(execution(setup.organization(), setup.execution())).containsEntry("status", "COMPLETED");
        assertThat(signals(setup.organization(), "record.updated")).isEqualTo(1);
    }

    @Test
    void aWorkerThatDiesBeforeTheEffectIsRecoveredTheSameWay() {
        Setup setup = setupWith(update("Set", value("fieldB__c", "done")));
        FailureInjection.crash("UPDATE_RECORD", 1, false);

        assertThatThrownBy(this::runWorker).isInstanceOf(FailureInjection.SimulatedCrash.class);
        theLeaseEnds(setup.organization());
        survivorLooks();

        assertThat(fieldOf(setup.admin(), setup.record(), "fieldB__c")).isEqualTo("done");
        assertThat(execution(setup.organization(), setup.execution())).containsEntry("status", "COMPLETED");
    }

    @Test
    void aNoticeIsMadeOnceAcrossADeathAfterItWasWritten() {
        Setup setup = setupWith("{\"type\":\"NOTIFY\",\"name\":\"Notice\",\"settings\":{\"recipient\":\"OWNER\"}}");
        FailureInjection.crash("NOTIFY", 1, true);

        assertThatThrownBy(this::runWorker).isInstanceOf(FailureInjection.SimulatedCrash.class);
        assertThat(scalar(setup.organization(), "select count(*) from notification")).as("rolled back with the step")
                .isZero();
        theLeaseEnds(setup.organization());
        survivorLooks();
        survivorLooks();

        assertThat(scalar(setup.organization(), "select count(*) from notification")).isEqualTo(1);
        assertThat(execution(setup.organization(), setup.execution())).containsEntry("status", "COMPLETED");
    }

    @Test
    void aStepThatCompletedBeforeTheDeathIsNotRunAgain() {
        Organization organization = organization();
        TestBrowser admin = adminWithData(organization);
        workflowExists(admin, "Mark", recordTrigger("RECORD_CREATED"), null, update("Set", value("fieldB__c", "done")),
                action("Say", "after.crash"));
        String id = record(admin, "{\"fieldA__c\":\"hello\"}");
        settle(organization);
        String execution = theExecution(organization);
        long before = versionOfRecord(admin, id);
        FailureInjection.crash("PUBLISH_EVENT", 1, true);

        assertThatThrownBy(this::runWorker).isInstanceOf(FailureInjection.SimulatedCrash.class);
        // The commit of the first step happens before the crash is even thrown, so this is already true; the short
        // wait is only insurance against a loaded CI runner being slow to hand back a fresh connection, not a real
        // race in the platform's own promise (ADR-0165).
        Awaitility.await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(50))
                .untilAsserted(() -> assertThat(steps(organization, execution))
                        .as("the first step committed before the death").contains("Set:COMPLETED:1"));
        theLeaseEnds(organization);
        survivorLooks();
        settle(organization);

        assertThat(execution(organization, execution)).containsEntry("status", "COMPLETED");
        assertThat(versionOfRecord(admin, id)).as("the completed step was not made again").isEqualTo(before + 1);
    }

    @Test
    void anExecutionThatKeepsKillingItsWorkerIsSetAsideAndStopsKillingWorkers() {
        Setup setup = setupWith(update("Set", value("fieldB__c", "done")));
        FailureInjection.crash("UPDATE_RECORD", 10, false);

        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(this::runWorker).isInstanceOf(FailureInjection.SimulatedCrash.class);
            theLeaseEnds(setup.organization());
        }
        runWorker();

        assertThat(execution(setup.organization(), setup.execution())).containsEntry("status", "DEAD_LETTERED");
        int calls = FailureInjection.calls("UPDATE_RECORD");
        runWorker();
        assertThat(FailureInjection.calls("UPDATE_RECORD")).as("no worker is sent to it again").isEqualTo(calls);
    }
}
