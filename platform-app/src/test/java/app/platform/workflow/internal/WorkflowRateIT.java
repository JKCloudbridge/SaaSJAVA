package app.platform.workflow.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestOrganizations.Organization;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * The last line against a flood (ADR-0140): past the number of executions an object may start in a minute the rest are
 * delayed by whole minutes and never dropped, so a flood drains at the rate the platform allows.
 */
@PlatformIntegrationTest
@TestPropertySource(properties = {
    "platform.outbox.enabled=true",
    "platform.outbox.poll-interval=50ms",
    "platform.workflow.enabled=false",
    "platform.workflow.limits.max-executions-per-minute=2"})
class WorkflowRateIT extends ActionsSupport {

    @Test
    void pastTheRateTheRestAreDelayedByWholeMinutesAndNothingIsDropped() {
        Organization organization = organization();
        TestBrowser admin = adminWithData(organization);
        workflowExists(admin, "Each", recordTrigger("RECORD_CREATED"), null, action("Tell", "each.event"));
        for (int i = 0; i < 5; i++) {
            record(admin, "{\"fieldA__c\":\"r" + i + "\"}");
            settle(organization);
        }

        List<Map<String, String>> found = rows(organization, "select reason, "
                + "round(extract(epoch from run_at - created_at) / 60) as minutes from workflow_execution "
                + "order by created_at, id");

        assertThat(found).hasSize(5);
        assertThat(found.stream().map(row -> row.get("reason") + "@" + row.get("minutes"))).containsExactly(
                "null@0", "null@0", "rate_limited@1", "rate_limited@1", "rate_limited@2");
    }

    @Test
    void aDelayedExecutionStaysWaitingUntilItsTimeAndThenRunsLikeAnyOther() {
        Organization organization = organization();
        TestBrowser admin = adminWithData(organization);
        workflowExists(admin, "Each", recordTrigger("RECORD_CREATED"), null, action("Tell", "each.event"));
        for (int i = 0; i < 4; i++) {
            record(admin, "{\"fieldA__c\":\"r" + i + "\"}");
            settle(organization);
        }

        runWorker();
        List<String> afterTheFirstRound = statuses(organization);
        makeDue(organization);
        runWorker();

        assertThat(afterTheFirstRound).containsExactly("COMPLETED", "COMPLETED", "QUEUED", "QUEUED");
        assertThat(statuses(organization)).containsOnly("COMPLETED");
        assertThat(signals(organization, "workflow.signal.each.event")).as("every one ran").isEqualTo(4);
    }
}
