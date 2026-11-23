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
 * Replay of a delivery that was set aside (Sprint 32, ADR-0278): the same delivery becomes a new generation with the
 * attempts counted afresh and the same delivery identifier; two clicks make one; the bulk replay is bounded and says
 * whether more remain; and replay is an ability of its own, apart from managing.
 */
@WebhookTest
class WebhookReplayIT extends WebhookSupport {

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
    void aDeadLetterIsReplayedAsANewGenerationWithTheSameIdentifierAndTwoClicksMakeOne() {
        Organization organization = webhookOrganization();
        TestBrowser admin = adminOf(organization);
        Hook hook = live(admin, receiver, EVENTS_CREATED);
        receiver.respondWith(400);
        createdEmployee(administrator(organization), "Replay me");
        await("the delivery to be set aside", () -> count(admin, hook, "DEAD_LETTERED") == 1);
        String delivery = firstDelivery(admin, hook, "DEAD_LETTERED");
        String firstIdentifier = receiver.of("record.created").get(0).id();
        receiver.respondWith(200);
        String path = WEBHOOKS + "/" + hook.id() + "/deliveries/" + delivery + "/replay";

        Response first = admin.postJson(path, "{}");
        Response second = admin.postJson(path, "{}");

        assertThat(first.status()).as(first.body()).isEqualTo(200);
        assertThat(JsonPath.<Integer>read(first.body(), "$.data.replayed")).isEqualTo(1);
        // the second click finds it no longer dead: nothing more is made
        assertThat(second.status()).isIn(200, 409);
        if (second.status() == 200) {
            assertThat(JsonPath.<Integer>read(second.body(), "$.data.replayed")).isZero();
        }
        await("the replay to arrive", () -> count(admin, hook, "DONE") == 1);

        assertThat(receiver.of("record.created")).hasSize(2);
        assertThat(receiver.of("record.created").get(1).id()).as("the same delivery identifier")
                .isEqualTo(firstIdentifier);
        assertThat(WebhookReceiver.verify(receiver.of("record.created").get(1), hook.secret(), 300)).isTrue();
        assertThat(JsonPath.<Integer>read(deliveries(admin, hook, "DONE").body(), "$.data.items[0].generation"))
                .isEqualTo(2);
        assertThat(JsonPath.<List<Integer>>read(attemptsOf(admin, hook, delivery).body(), "$.data[*].generation"))
                .containsExactly(1, 2);
    }

    @Test
    void aDeliveryThatIsNotSetAsideCannotBeReplayed() {
        Organization organization = webhookOrganization();
        TestBrowser admin = adminOf(organization);
        Hook hook = live(admin, receiver, EVENTS_CREATED);
        createdEmployee(administrator(organization), "Fine");
        await("the delivery to be done", () -> count(admin, hook, "DONE") == 1);
        String delivery = firstDelivery(admin, hook, "DONE");

        Response replay = admin.postJson(WEBHOOKS + "/" + hook.id() + "/deliveries/" + delivery + "/replay", "{}");

        assertThat(replay.status()).isEqualTo(409);
        assertThat(count(admin, hook, "DONE")).isEqualTo(1);
    }

    @Test
    void theBulkReplayPutsEveryDeadLetterBackAndSaysWhetherMoreRemain() {
        Organization organization = webhookOrganization();
        TestBrowser admin = adminOf(organization);
        Hook hook = live(admin, receiver, EVENTS_CREATED);
        receiver.respondWith(400);
        Caller program = administrator(organization);
        for (int i = 0; i < 3; i++) {
            createdEmployee(program, "Bulk " + i);
        }
        await("three dead letters", () -> count(admin, hook, "DEAD_LETTERED") == 3);
        receiver.respondWith(200);

        Response replayed = admin.postJson(WEBHOOKS + "/" + hook.id() + "/deliveries/replay", "{}");

        assertThat(replayed.status()).as(replayed.body()).isEqualTo(200);
        assertThat(JsonPath.<Integer>read(replayed.body(), "$.data.replayed")).isEqualTo(3);
        assertThat(JsonPath.<Boolean>read(replayed.body(), "$.data.more")).isFalse();
        await("all three to arrive", () -> count(admin, hook, "DONE") == 3);
        assertThat(count(admin, hook, "DEAD_LETTERED")).isZero();
    }

    @Test
    void replayNeedsItsOwnAbilityAndManagingDoesNotGiveIt() {
        Organization organization = webhookOrganization();
        TestBrowser admin = adminOf(organization);
        Hook hook = live(admin, receiver, EVENTS_CREATED);
        receiver.respondWith(400);
        createdEmployee(administrator(organization), "Needs replay");
        await("the delivery to be set aside", () -> count(admin, hook, "DEAD_LETTERED") == 1);
        String delivery = firstDelivery(admin, hook, "DEAD_LETTERED");
        TestBrowser manager = as(organization, memberWithAbilitiesAndMatrix(organization, admin, null,
                "webhooks.view", "webhooks.manage").member());

        Response refused = manager.postJson(WEBHOOKS + "/" + hook.id() + "/deliveries/" + delivery + "/replay", "{}");
        Response bulkRefused = manager.postJson(WEBHOOKS + "/" + hook.id() + "/deliveries/replay", "{}");

        assertThat(refused.status()).isEqualTo(403);
        assertThat(bulkRefused.status()).isEqualTo(403);
        assertThat(count(admin, hook, "DEAD_LETTERED")).isEqualTo(1);
    }
}
