package app.platform.workflow.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestOrganizations.Organization;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * The fair share of the worker (ADR-0164): one organization with a long queue cannot keep the others waiting. With a
 * cap of three executions per organization in one look, an organization that queued nine is served three at a time, the
 * small organizations that queued later are served in the very first look, nothing is lost, and the whole queue is
 * still worked off by repeated looks.
 */
@PlatformIntegrationTest
@TestPropertySource(properties = {
    "platform.outbox.enabled=true",
    "platform.outbox.poll-interval=50ms",
    "platform.workflow.enabled=false",
    "platform.workflow.worker.per-organization=3",
    "platform.workflow.worker.batch-size=100"})
class FairShareIT extends WorkflowSupport {

    private record Setup(Organization organization, TestBrowser admin) {
    }

    private Setup queued(int records) {
        Organization organization = organization();
        TestBrowser admin = adminOf(organization);
        workflowExists(admin, "Tell", recordTrigger("RECORD_CREATED"), null, action("Say", "fair.share"));
        for (int i = 0; i < records; i++) {
            record(admin, "{\"fieldA__c\":\"r" + i + "\"}");
        }
        settle(organization);
        assertThat(scalar(organization, "select count(*) from workflow_execution where status = 'QUEUED'"))
                .isEqualTo(records);
        return new Setup(organization, admin);
    }

    private static long completed(Organization organization) {
        return scalar(organization, "select count(*) from workflow_execution where status = 'COMPLETED'");
    }

    @Test
    void aBusyOrganizationIsServedInTurnsAndTheSmallOnesAreServedInTheFirstLook() {
        Setup busy = queued(9);
        Setup small = queued(2);
        Setup other = queued(2);

        int claimed = dispatcher.pollExecutionsOnce();

        assertThat(claimed).isGreaterThanOrEqualTo(7);
        assertThat(completed(busy.organization())).as("the cap, however long the queue").isEqualTo(3);
        assertThat(completed(small.organization())).as("served in the first look though it queued later")
                .isEqualTo(2);
        assertThat(completed(other.organization())).isEqualTo(2);
    }

    @Test
    void repeatedLooksWorkOffTheWholeQueueAndLoseNothing() {
        Setup busy = queued(9);

        runWorker();

        assertThat(completed(busy.organization())).isEqualTo(9);
        assertThat(scalar(busy.organization(), "select count(*) from workflow_execution where status not in "
                + "('COMPLETED')")).isZero();
    }

    @Test
    void theCountsOfOneOrganizationNeverCountAgainstTheCapOfAnother() {
        Setup first = queued(4);
        Setup second = queued(4);

        dispatcher.pollExecutionsOnce();

        assertThat(completed(first.organization())).isEqualTo(3);
        assertThat(completed(second.organization())).isEqualTo(3);
    }
}
