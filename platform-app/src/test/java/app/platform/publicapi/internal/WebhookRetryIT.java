package app.platform.publicapi.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.integration.WebhookReceiver;
import app.platform.integration.WebhookReceiver.Received;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations.Organization;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Retries of a webhook delivery (Sprint 32, ADR-0279): the receiver fails and then recovers; the platform waits longer
 * each time, keeps one delivery with several attempts, carries the same delivery identifier every time, and never
 * makes a second delivery of the same event. A refusal that waiting cannot mend is set aside at once; a receiver that
 * answers with a redirect is not followed.
 */
@WebhookTest
class WebhookRetryIT extends WebhookSupport {

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
    void aReceiverThatFailsAndRecoversGetsOneDeliveryWithSeveralAttemptsAndOneIdentifier() {
        Organization organization = webhookOrganization();
        TestBrowser admin = adminOf(organization);
        Hook hook = live(admin, receiver, EVENTS_CREATED);
        receiver.failNext(2, 503);

        createdEmployee(administrator(organization), "Retry me");
        await("the delivery to be done after the retries", () -> count(admin, hook, "DONE") == 1);

        List<Received> arrivals = receiver.of("record.created");
        assertThat(arrivals).as("two failures and one success").hasSize(3);
        Set<String> identifiers = arrivals.stream().map(Received::id).collect(Collectors.toSet());
        assertThat(identifiers).as("the same delivery identifier every time").hasSize(1);
        assertThat(arrivals.stream().map(one -> one.header("Webhook-Attempt")).toList()).containsExactly("1", "2",
                "3");
        assertThat(arrivals).allMatch(one -> WebhookReceiver.verify(one, hook.secret(), 300));
        // the waits grow: the second gap is longer than the first
        long first = java.time.Duration.between(arrivals.get(0).at(), arrivals.get(1).at()).toMillis();
        long second = java.time.Duration.between(arrivals.get(1).at(), arrivals.get(2).at()).toMillis();
        assertThat(second).isGreaterThan(first);

        String delivery = firstDelivery(admin, hook, "DONE");
        Response attempts = attemptsOf(admin, hook, delivery);
        assertThat(JsonPath.<List<String>>read(attempts.body(), "$.data[*].outcome")).containsExactly("RETRY", "RETRY",
                "SUCCESS");
        assertThat(JsonPath.<List<String>>read(attempts.body(), "$.data[*].reason")).containsExactly("unavailable",
                "unavailable", null);
        assertThat(JsonPath.<List<String>>read(attempts.body(), "$.data[*].statusClass")).containsExactly("5xx", "5xx",
                "2xx");
        assertThat(count(admin, hook, "DEAD_LETTERED")).isZero();
        assertThat(JsonPath.<String>read(read(admin, hook).body(), "$.data.state")).isEqualTo("ACTIVE");
    }

    @Test
    void aReceiverThatRefusesAndNeverMendsHasTheDeliverySetAsideAtOnce() {
        Organization organization = webhookOrganization();
        TestBrowser admin = adminOf(organization);
        Hook hook = live(admin, receiver, EVENTS_CREATED);
        receiver.respondWith(400);

        createdEmployee(administrator(organization), "Refused");
        await("the delivery to be set aside", () -> count(admin, hook, "DEAD_LETTERED") == 1);

        assertThat(receiver.of("record.created")).as("a refusal is not tried again").hasSize(1);
        String delivery = firstDelivery(admin, hook, "DEAD_LETTERED");
        assertThat(JsonPath.<String>read(deliveries(admin, hook, "DEAD_LETTERED").body(), "$.data.items[0].reason"))
                .isEqualTo("rejected");
        assertThat(attemptsOf(admin, hook, delivery).body()).contains("PERMANENT");
    }

    @Test
    void aGoneReceiverIsNotAskedAgain() {
        Organization organization = webhookOrganization();
        TestBrowser admin = adminOf(organization);
        Hook hook = live(admin, receiver, EVENTS_CREATED);
        receiver.respondWith(410);

        createdEmployee(administrator(organization), "Gone");
        await("the delivery to be set aside", () -> count(admin, hook, "DEAD_LETTERED") == 1);

        assertThat(receiver.of("record.created")).hasSize(1);
    }

    @Test
    void aRedirectIsNeverFollowed() {
        Organization organization = webhookOrganization();
        TestBrowser admin = adminOf(organization);
        Hook hook = live(admin, receiver, EVENTS_CREATED);
        try (WebhookReceiver elsewhere = new WebhookReceiver()) {
            receiver.redirectTo(elsewhere.address("/stolen"));

            createdEmployee(administrator(organization), "Redirected");
            await("the delivery to be set aside", () -> count(admin, hook, "DEAD_LETTERED") == 1);

            assertThat(elsewhere.requests()).as("the platform never goes where it was sent").isZero();
            assertThat(JsonPath.<String>read(deliveries(admin, hook, "DEAD_LETTERED").body(),
                    "$.data.items[0].reason")).isEqualTo("redirect_refused");
        }
    }

    @Test
    void anUnbrokenRunOfFailuresMakesTheSubscriptionFailingAndASuccessMakesItActiveAgain() {
        Organization organization = webhookOrganization();
        TestBrowser admin = adminOf(organization);
        Hook hook = live(admin, receiver, EVENTS_CREATED);
        receiver.respondWith(500);

        createdEmployee(administrator(organization), "First");
        await("the subscription to show it is failing",
                () -> "FAILING".equals(JsonPath.read(read(admin, hook).body(), "$.data.state")));
        receiver.respondWith(200);
        createdEmployee(administrator(organization), "Second");

        await("the subscription to be active again",
                () -> "ACTIVE".equals(JsonPath.read(read(admin, hook).body(), "$.data.state")));
    }
}
