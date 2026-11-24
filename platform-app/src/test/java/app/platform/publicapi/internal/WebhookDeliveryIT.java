package app.platform.publicapi.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.integration.WebhookReceiver;
import app.platform.integration.WebhookReceiver.Received;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations.Organization;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The first exit test of Sprint 32 (ADR-0274 to ADR-0278): a member makes a subscription, its address is verified by a
 * signed ping, a record is created through the public door, and one signed delivery arrives at the receiver with the
 * right headers and a signature the test verifies by itself, carrying identifiers only.
 */
@WebhookTest
class WebhookDeliveryIT extends WebhookSupport {

    private WebhookReceiver receiver;

    @BeforeEach
    void startReceiver() {
        receiver = new WebhookReceiver();
    }

    @AfterEach
    void stopReceiver() {
        receiver.close();
    }

    @Test
    void aRecordCreatedThroughThePublicDoorArrivesOnceSignedAndWithIdentifiersOnly() {
        Organization organization = webhookOrganization();
        TestBrowser admin = adminOf(organization);
        Caller program = administrator(organization);
        Hook hook = create(admin, receiver, EVENTS_CREATED);

        // the verification: a signed ping, answered 2xx
        Response tested = sendTest(admin, hook);
        assertThat(tested.status()).as(tested.body()).isEqualTo(200);
        assertThat(JsonPath.<Boolean>read(tested.body(), "$.data.delivered")).isTrue();
        assertThat(JsonPath.<Boolean>read(tested.body(), "$.data.verified")).isTrue();
        assertThat(receiver.of("ping")).hasSize(1);
        assertThat(WebhookReceiver.verify(receiver.of("ping").get(0), hook.secret(), 300)).isTrue();
        assertThat(switchOn(admin, hook).status()).isEqualTo(200);
        receiver.clear();

        String employee = createdEmployee(program, "A. Example");
        await("the delivery", () -> receiver.of("record.created").size() >= 1);

        Received delivery = receiver.of("record.created").get(0);
        assertThat(delivery.header("Content-Type")).startsWith("application/json");
        assertThat(delivery.header("Webhook-Id")).matches("[0-9a-f-]{36}");
        assertThat(delivery.header("Webhook-Event")).isEqualTo("record.created");
        assertThat(delivery.header("Webhook-Attempt")).isEqualTo("1");
        assertThat(delivery.header("Webhook-Timestamp")).matches("[0-9]{10}");
        assertThat(WebhookReceiver.verify(delivery, hook.secret(), 300)).as("the signature").isTrue();
        assertThat(WebhookReceiver.signatures(delivery)).isEqualTo(1);
        assertThat(JsonPath.<String>read(delivery.text(), "$.id")).isEqualTo(delivery.id());
        assertThat(JsonPath.<String>read(delivery.text(), "$.type")).isEqualTo("record.created");
        assertThat(JsonPath.<String>read(delivery.text(), "$.object")).isEqualTo("Employee__c");
        assertThat(JsonPath.<String>read(delivery.text(), "$.recordId")).isEqualTo(employee);
        assertThat(JsonPath.<Integer>read(delivery.text(), "$.payloadVersion")).isEqualTo(1);
        // identifiers only: no field name, no value
        List<String> properties = new ArrayList<>(JsonPath.<java.util.Map<String, Object>>read(delivery.text(), "$")
                .keySet());
        assertThat(properties).containsExactlyInAnyOrder("payloadVersion", "id", "type", "createdAt", "object",
                "recordId", "version");
        assertThat(delivery.text()).doesNotContain("name__c").doesNotContain("A. Example").doesNotContain("Sales");

        await("the delivery to be recorded as done", () -> count(admin, hook, "DONE") == 1);
        assertThat(JsonPath.<String>read(deliveries(admin, hook, "DONE").body(), "$.data.items[0].id"))
                .isEqualTo(delivery.id());
        assertThat(receiver.of("record.created")).as("one event, one delivery").hasSize(1);
    }

    @Test
    void onlyTheEventsAskedForAndOnlyTheObjectsListedAreDelivered() {
        Organization organization = webhookOrganization();
        TestBrowser admin = adminOf(organization);
        Caller program = administrator(organization);
        Hook updates = create(admin, hookName(), receiver.address("/updates"), "[\"record.updated\"]",
                "[\"Employee__c\"]");
        assertThat(JsonPath.<Boolean>read(sendTest(admin, updates).body(), "$.data.delivered")).isTrue();
        assertThat(switchOn(admin, updates).status()).isEqualTo(200);
        receiver.clear();

        String id = createdEmployee(program, "Created");
        Response read = program.get(EMPLOYEE_RECORDS + "/" + id);
        program.patch(EMPLOYEE_RECORDS + "/" + id, "{\"fields\":{\"department__c\":\"Support\"}}", "If-Match",
                etag(read));
        await("the update", () -> receiver.of("record.updated").size() >= 1);

        assertThat(receiver.of("record.created")).as("the event was not asked for").isEmpty();
        assertThat(receiver.of("record.updated")).hasSize(1);
        assertThat(JsonPath.<Integer>read(receiver.of("record.updated").get(0).text(), "$.version")).isEqualTo(1);
    }

    @Test
    void aReceiverThatAnswersWithAPageIsNotAFailureAndNothingOfItIsKept() {
        Organization organization = webhookOrganization();
        TestBrowser admin = adminOf(organization);
        receiver.largeAnswer(true);
        Hook hook = live(admin, receiver, EVENTS_CREATED);

        createdEmployee(administrator(organization), "Large");
        await("the delivery to be done", () -> count(admin, hook, "DONE") == 1);

        assertThat(deliveries(admin, hook, "DEAD_LETTERED").body()).doesNotContain("xxxxxxxx");
        assertThat(deliveries(admin, hook, "DONE").body()).doesNotContain("xxxxxxxx");
    }

    @Test
    void aSwitchedOffSubscriptionIsToldNothingAndWhatWasMissedIsNotInvented() {
        Organization organization = webhookOrganization();
        TestBrowser admin = adminOf(organization);
        Hook hook = live(admin, receiver, EVENTS_CREATED);
        assertThat(switchOff(admin, hook).status()).isEqualTo(200);

        createdEmployee(administrator(organization), "While off");
        // nothing is queued for a subscription that is off, so there is nothing to wait for: look for a while
        try {
            Thread.sleep(Duration.ofSeconds(2).toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }

        assertThat(receiver.received()).isEmpty();
        assertThat(JsonPath.<Integer>read(read(admin, hook).body(), "$.data.queue.pending")).isZero();
    }
}
