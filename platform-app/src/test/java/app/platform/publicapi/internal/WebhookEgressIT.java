package app.platform.publicapi.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.integration.WebhookReceiver;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations.Organization;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The address of a webhook is attacker-controlled input to a server that has a network (Sprint 32, ADR-0276): a
 * private,
 * loopback, link-local or otherwise hostile address is refused when the subscription is made and never connected to; an
 * address that is changed needs a new verification before the subscription can be switched on again; and a refusal
 * never
 * repeats what was typed.
 */
@WebhookTest
class WebhookEgressIT extends WebhookSupport {

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
    void everyHostileAddressIsRefusedWhenTheSubscriptionIsMade() {
        Organization organization = webhookOrganization();
        TestBrowser admin = adminOf(organization);
        List<String> hostile = List.of("https://localhost/hooks", "https://127.0.0.2/hooks", "https://[::1]/hooks",
                "https://10.0.0.5/hooks", "https://192.168.1.10/hooks", "https://169.254.169.254/latest/meta-data",
                "https://metadata.internal/hooks", "https://intranet.corp/hooks", "http://hooks.example.com/x",
                "https://user:topsecret@hooks.example.com/x", "https://hooks.example.com/x?token=1",
                "https://hooks.example.com/../admin", "ftp://hooks.example.com/x", "hooks.example.com/x");

        for (String address : hostile) {
            Response refused = admin.postJson(WEBHOOKS, saveBody(hookName(), address, EVENTS_CREATED, null, 0));

            assertThat(refused.status()).as(address).isEqualTo(400);
            assertThat(JsonPath.<List<String>>read(refused.body(), "$.error.fields.address")).as(address)
                    .isNotEmpty();
            assertThat(refused.body()).as("a refusal never repeats what was typed").doesNotContain("topsecret");
        }
        assertThat(JsonPath.<List<?>>read(admin.get(WEBHOOKS).body(), "$.data")).as("nothing was made").isEmpty();
    }

    @Test
    void anAddressThatIsChangedNeedsANewVerificationBeforeTheSubscriptionIsSwitchedOnAgain() {
        Organization organization = webhookOrganization();
        TestBrowser admin = adminOf(organization);
        Hook hook = live(admin, receiver, EVENTS_CREATED);
        String name = JsonPath.read(read(admin, hook).body(), "$.data.name");

        // a subscription that is on cannot be changed
        long version = versionOf(admin, hook);
        Response whileOn = admin.request("PUT", WEBHOOKS + "/" + hook.id(), saveBody(name,
                receiver.address("/moved"), EVENTS_CREATED, null, version));
        assertThat(whileOn.status()).isEqualTo(409);

        assertThat(switchOff(admin, hook).status()).isEqualTo(200);
        Response changed = admin.request("PUT", WEBHOOKS + "/" + hook.id(), saveBody(name,
                receiver.address("/moved"), EVENTS_CREATED, null, versionOf(admin, hook)));
        assertThat(changed.status()).as(changed.body()).isEqualTo(200);
        assertThat(JsonPath.<Boolean>read(changed.body(), "$.data.verified")).as("the old verification is void")
                .isFalse();

        Response refused = switchOn(admin, hook);
        assertThat(refused.status()).isEqualTo(409);
        pause(1100);
        assertThat(JsonPath.<Boolean>read(sendTest(admin, hook).body(), "$.data.verified")).isTrue();
        assertThat(switchOn(admin, hook).status()).isEqualTo(200);
        assertThat(receiver.of("ping").stream().map(WebhookReceiver.Received::path).toList()).contains("/moved");
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void aTestToAnAddressThatDoesNotAnswerIsAReasonAndNeverVerifies() {
        Organization organization = webhookOrganization();
        TestBrowser admin = adminOf(organization);
        Hook hook = create(admin, receiver, EVENTS_CREATED);
        receiver.respondWith(500);

        Response tested = sendTest(admin, hook);

        assertThat(tested.status()).isEqualTo(200);
        assertThat(JsonPath.<Boolean>read(tested.body(), "$.data.delivered")).isFalse();
        assertThat(JsonPath.<Boolean>read(tested.body(), "$.data.verified")).isFalse();
        assertThat(JsonPath.<String>read(tested.body(), "$.data.reason")).isEqualTo("server_error");
        assertThat(JsonPath.<String>read(tested.body(), "$.data.statusClass")).isEqualTo("5xx");
        assertThat(switchOn(admin, hook).status()).as("never switched on without a verification").isEqualTo(409);
    }

    @Test
    void testsAreRateLimitedForOneSubscription() throws InterruptedException {
        Organization organization = webhookOrganization();
        TestBrowser admin = adminOf(organization);
        Hook hook = create(admin, receiver, EVENTS_CREATED);

        Response first = sendTest(admin, hook);
        Response second = sendTest(admin, hook);

        assertThat(first.status()).isEqualTo(200);
        assertThat(second.status()).isEqualTo(429);
        assertThat(second.header("Retry-After")).isPresent();
        Thread.sleep(1100);
        assertThat(sendTest(admin, hook).status()).isEqualTo(200);
    }

    @Test
    void theNumberOfSubscriptionsOfAnOrganizationIsBounded() {
        Organization organization = webhookOrganization();
        TestBrowser admin = adminOf(organization);

        int made = 0;
        Response last = null;
        for (int i = 0; i < 52; i++) {
            last = admin.postJson(WEBHOOKS, saveBody(hookName(), receiver.address("/h" + i), EVENTS_CREATED, null, 0));
            if (last.status() == 201) {
                made++;
            } else {
                break;
            }
        }

        assertThat(made).isEqualTo(50);
        assertThat(last.status()).isEqualTo(409);
    }
}
