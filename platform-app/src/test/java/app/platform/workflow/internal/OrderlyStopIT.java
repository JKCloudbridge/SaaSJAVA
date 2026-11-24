package app.platform.workflow.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.sharedkernel.lifecycle.WorkerStop;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestApplication;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestOrganizations.Organization;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/**
 * The orderly stop of a real second instance in the middle of work (Sprint 33, ADR-0289), the counterpart of
 * {@link WorkerCrashIT}: there the process dies without a word, here it is told to stop, as a rolling release tells it.
 *
 * <p>The promises are three. The stop is bounded: it ends within the grace period and so well inside the time the
 * container platform allows. Nothing is lost: whatever the leaving instance had claimed and did not finish is finished by
 * the one that stays (immediately when it released the claim, after the lease when it did not). Nothing is repeated: a
 * record the leaving instance had already changed is not changed a second time, so every record carries exactly one
 * change and exactly one change was announced for each.
 */
@PlatformIntegrationTest
@Import(FailureInjection.class)
@TestPropertySource(properties = {
    "platform.outbox.enabled=true",
    "platform.outbox.poll-interval=50ms",
    "platform.workflow.enabled=false"})
class OrderlyStopIT extends ActionsSupport {

    private static final int RECORDS = 30;

    @Test
    void anInstanceToldToStopMidWorkLeavesNothingUndoneAndNothingDoneTwice() {
        Organization organization = organization();
        TestBrowser admin = adminWithData(organization);
        workflowExists(admin, "Mark", recordTrigger("RECORD_CREATED"), null, update("Set", value("fieldB__c", "done")));
        TestApplication leaving = TestApplication.start("platform.outbox.enabled=true",
                "platform.outbox.poll-interval=50ms", "platform.workflow.enabled=true",
                "platform.workflow.worker.poll-interval=50ms");
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < RECORDS; i++) {
            ids.add(record(admin, "{\"fieldA__c\":\"hello " + i + "\"}"));
        }

        long started = System.nanoTime();
        leaving.close();
        long tookMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();

        assertThat(Duration.ofMillis(tookMillis)).as("the stop is bounded by the grace period, with room for the "
                + "web server's own shutdown").isLessThan(WorkerStop.GRACE.plusSeconds(15));

        // What the leaving instance held and did not finish is claimed again once the lease has ended; an orderly stop
        // should leave few such rows, and a row it finished is not among them.
        run(organization, "update workflow_execution set locked_until = now() - interval '1 minute', "
                + "version = version + 1 where status = 'RUNNING'");
        settle(organization);
        runWorker();
        settle(organization);
        runWorker();

        Set<Long> versions = new TreeSet<>();
        for (String id : ids) {
            assertThat(fieldOf(admin, id, "fieldB__c")).as("every record was changed").isEqualTo("done");
            versions.add(versionOfRecord(admin, id));
        }
        assertThat(versions).as("every record carries the same single change, none was changed twice").hasSize(1);
        assertThat(signals(organization, "record.updated")).as("one announcement for each change").isEqualTo(RECORDS);
        assertThat(statuses(organization)).as("nothing is left half done").containsOnly("COMPLETED");
    }
}
