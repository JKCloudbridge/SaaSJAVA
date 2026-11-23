package app.platform.publicapi.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.integration.WebhookReceiver;
import app.platform.sharedkernel.ActorId;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestOrganizations.Organization;
import app.platform.testsupport.tenancy.TenantFixtures;
import com.jayway.jsonpath.JsonPath;
import java.sql.SQLException;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * Fair share between organizations (Sprint 32, ADR-0279): one organization with a deep queue does not starve another.
 * The worker takes at most a few deliveries of one organization in a claim, by turns, so the single event of a quiet
 * organization is sent while the busy one is still working through its queue.
 */
@WebhookTest
@TestPropertySource(properties = {
    "platform.webhooks.worker.per-organization=2",
    "platform.webhooks.worker.max-concurrent-sends=4"})
class WebhookFairShareIT extends WebhookSupport {

    private WebhookReceiver slow;
    private WebhookReceiver quick;

    @BeforeEach
    void startReceivers() {
        slow = new WebhookReceiver();
        quick = new WebhookReceiver();
    }

    @AfterEach
    void stopReceivers() {
        slow.close();
        quick.close();
    }

    @Test
    void anOrganizationWithAThousandEventsDoesNotStarveAnother() throws SQLException {
        Organization busy = webhookOrganization();
        Organization quiet = webhookOrganization();
        TestBrowser busyAdmin = adminOf(busy);
        TestBrowser quietAdmin = adminOf(quiet);
        Hook busyHook = live(busyAdmin, slow, EVENTS_CREATED);
        Hook quietHook = live(quietAdmin, quick, EVENTS_CREATED);
        String record = createdEmployee(administrator(busy), "The record every queued event is about");
        await("the ordinary delivery of the busy organization", () -> count(busyAdmin, busyHook, "DONE") == 1);
        slow.clear();
        slow.delay(Duration.ofMillis(300));
        // A deep queue, made in one statement: a thousand events of the busy organization are due now.
        TenantFixtures.asTenant(busy.id(), connection -> TenantFixtures.update(connection,
                "insert into webhook_delivery (tenant_id, subscription_id, event_id, event_type, object_api_name, "
                        + "record_id, created_by, updated_by) select ?, ?, gen_random_uuid(), 'record.created', "
                        + "'Employee__c', ?, ?, ? from generate_series(1, 1000)", busy.id().value(),
                UUID.fromString(busyHook.id()), UUID.fromString(record), ActorId.SYSTEM.value(),
                ActorId.SYSTEM.value()));

        createdEmployee(administrator(quiet), "The quiet organization's only event");
        await("the quiet organization's delivery", () -> quick.of("record.created").size() == 1);

        assertThat(slow.of("record.created").size()).as("the busy queue was far from drained when the quiet one "
                + "was served").isLessThan(300);
        assertThat(JsonPath.<Integer>read(read(busyAdmin, busyHook).body(), "$.data.queue.pending"))
                .as("the busy organization still has work").isGreaterThan(100);
        assertThat(count(quietAdmin, quietHook, "DONE")).isEqualTo(1);
    }
}
