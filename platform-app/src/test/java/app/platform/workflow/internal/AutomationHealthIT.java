package app.platform.workflow.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.identity.PlatformRole;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations.Organization;
import app.platform.testsupport.TestPlatform;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * The failure view of automation (ADR-0163): an administrator sees counts of what waits, what is set aside and what is
 * stuck, for their own organization only; the console sees the totals across organizations; neither shows a name, a
 * value or a text; a count follows the rows (a queue fills and drains, a failure is set aside until a person acts).
 */
@PlatformIntegrationTest
@TestPropertySource(properties = {
    "platform.outbox.enabled=true",
    "platform.outbox.poll-interval=50ms",
    "platform.workflow.enabled=false"})
class AutomationHealthIT extends ActionsSupport {

    private static final String HEALTH = "/api/v1/automation/health";
    private static final String PLATFORM_HEALTH = "/api/v1/platform/automation/health";

    private static long count(Response health, String key) {
        List<Number> found = JsonPath.read(health.body(), "$.data.sections[*].items[?(@.key=='" + key + "')].count");
        assertThat(found).as(key + " in " + health.body()).hasSize(1);
        return found.get(0).longValue();
    }

    private static boolean attention(Response health, String key) {
        List<Boolean> found = JsonPath.read(health.body(),
                "$.data.sections[*].items[?(@.key=='" + key + "')].needsAttention");
        return found.get(0);
    }

    @Test
    void theQueueFillsAndDrainsAndTheViewFollowsTheRows() {
        Organization organization = organization();
        TestBrowser admin = adminOf(organization);
        workflowExists(admin, "Tell", recordTrigger("RECORD_CREATED"), null, action("Say", "health.check"));
        record(admin, "{\"fieldA__c\":\"x\"}");
        record(admin, "{\"fieldA__c\":\"y\"}");
        settle(organization);

        Response waiting = admin.get(HEALTH);
        assertThat(waiting.status()).as(waiting.body()).isEqualTo(200);
        assertThat(count(waiting, "workflows.queued")).isEqualTo(2);
        assertThat(attention(waiting, "workflows.deadLettered")).isFalse();

        runWorker();

        Response drained = admin.get(HEALTH);
        assertThat(count(drained, "workflows.queued")).isZero();
        assertThat(count(drained, "workflows.deadLettered")).isZero();
    }

    @Test
    void anExecutionSetAsideForAPersonNeedsAttentionUntilTheyActOnIt() {
        Organization organization = organization();
        TestBrowser admin = adminOf(organization);
        addField(admin, OBJECT, "reviewer", "LOOKUP", ",\"settings\":{\"targetObject\":\"User\"}");
        workflowExists(admin, "Tell", recordTrigger("RECORD_CREATED"), null,
                "{\"type\":\"NOTIFY\",\"name\":\"Notice\",\"settings\":{\"recipient\":\"FIELD\","
                        + "\"recipientField\":\"reviewer__c\"}}");
        record(admin, "{\"fieldA__c\":\"x\"}");
        settle(organization);

        runWorker();

        Response health = admin.get(HEALTH);
        assertThat(count(health, "workflows.deadLettered")).isEqualTo(1);
        assertThat(attention(health, "workflows.deadLettered")).isTrue();
        assertThat(health.body()).as("counts only").doesNotContain("reviewer__c").doesNotContain("recipient_missing");
    }

    @Test
    void oneOrganizationNeverSeesTheCountsOfAnother() {
        Organization busy = organization();
        Organization quiet = organization();
        TestBrowser busyAdmin = adminOf(busy);
        workflowExists(busyAdmin, "Tell", recordTrigger("RECORD_CREATED"), null, action("Say", "health.check"));
        record(busyAdmin, "{\"fieldA__c\":\"x\"}");
        settle(busy);

        Response theirs = adminOf(quiet).get(HEALTH);

        assertThat(count(theirs, "workflows.queued")).isZero();
        assertThat(count(busyAdmin.get(HEALTH), "workflows.queued")).isEqualTo(1);
    }

    @Test
    void theConsoleSeesTheTotalsAcrossOrganizationsAndNoName() {
        Organization first = organization();
        Organization second = organization();
        for (Organization organization : List.of(first, second)) {
            TestBrowser admin = adminOf(organization);
            workflowExists(admin, "Tell", recordTrigger("RECORD_CREATED"), null, action("Say", "health.check"));
            record(admin, "{\"fieldA__c\":\"x\"}");
            settle(organization);
        }
        TestBrowser console = TestPlatform.signedIn(port, TestPlatform.person(users, PlatformRole.PLATFORM_SUPPORT));
        dispatcher.sampleQueue();

        Response health = console.get(PLATFORM_HEALTH);

        assertThat(health.status()).as(health.body()).isEqualTo(200);
        assertThat(count(health, "workflows.queued")).isGreaterThanOrEqualTo(2);
        assertThat(health.body()).doesNotContain(first.host()).doesNotContain(second.host())
                .doesNotContain(first.id().value().toString());
    }
}
