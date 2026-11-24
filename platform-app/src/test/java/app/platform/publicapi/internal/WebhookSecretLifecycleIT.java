package app.platform.publicapi.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.integration.WebhookReceiver;
import app.platform.integration.WebhookReceiver.Received;
import app.platform.testsupport.TestApplication;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations;
import app.platform.testsupport.TestOrganizations.Organization;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The signing secret of a subscription (Sprint 32, ADR-0277): shown once and never read back, replaced with an overlap
 * during which two signatures are sent and either is enough, ended at once on request, removed when it leaked so that
 * no
 * signature of it is ever made again, and the same on a second instance of the application.
 */
@WebhookTest
class WebhookSecretLifecycleIT extends WebhookSupport {

    private WebhookReceiver receiver;

    @BeforeEach
    void startReceiver() {
        receiver = new WebhookReceiver();
    }

    @AfterEach
    void stopReceiver() {
        receiver.close();
    }

    private Received deliverOne(Organization organization, TestBrowser admin, Hook hook, String name) {
        int before = receiver.of("record.created").size();
        createdEmployee(administrator(organization), name);
        try {
            await("the delivery of " + name, () -> receiver.of("record.created").size() > before);
        } catch (org.awaitility.core.ConditionTimeoutException lost) {
            // Say what the queue holds (states, stable reasons, attempts: no value) so that a failure explains itself.
            throw new AssertionError("no delivery of " + name + " arrived; the delivery log says: "
                    + deliveries(admin, hook).body(), lost);
        }
        return receiver.of("record.created").get(before);
    }

    @Test
    void theSecretIsShownOnceAndNoCallEverReturnsIt() {
        Organization organization = webhookOrganization();
        TestBrowser admin = adminOf(organization);

        Hook hook = create(admin, receiver, EVENTS_CREATED);

        assertThat(hook.secret()).startsWith("whsec_").hasSizeGreaterThan(40);
        assertThat(hook.response().header("Cache-Control").orElse("")).contains("no-store");
        assertThat(read(admin, hook).body()).doesNotContain(hook.secret()).doesNotContain("whsec_");
        assertThat(admin.get(WEBHOOKS).body()).doesNotContain(hook.secret()).doesNotContain("whsec_");
        assertThat(JsonPath.<Integer>read(read(admin, hook).body(), "$.data.secretNumber")).isEqualTo(1);
    }

    @Test
    void duringAnOverlapTwoSignaturesAreSentAndEitherSecretVerifiesTheDelivery() {
        Organization organization = webhookOrganization();
        TestBrowser admin = adminOf(organization);
        Hook hook = live(admin, receiver, EVENTS_CREATED);

        Response rotated = rotateSecret(admin, hook, 1);
        String replacement = JsonPath.read(rotated.body(), "$.data.secret");
        Received delivery = deliverOne(organization, admin, hook, "During the overlap");

        assertThat(rotated.status()).as(rotated.body()).isEqualTo(200);
        assertThat(replacement).isNotEqualTo(hook.secret());
        assertThat(JsonPath.<Integer>read(rotated.body(), "$.data.detail.secretNumber")).isEqualTo(2);
        assertThat(JsonPath.<String>read(rotated.body(), "$.data.detail.previousSecretUntil")).isNotNull();
        assertThat(WebhookReceiver.signatures(delivery)).isEqualTo(2);
        assertThat(WebhookReceiver.verify(delivery, hook.secret(), 300)).as("the old secret").isTrue();
        assertThat(WebhookReceiver.verify(delivery, replacement, 300)).as("the new secret").isTrue();
    }

    @Test
    void endingTheOverlapAtOnceLeavesOnlyTheNewSecretSigning() {
        Organization organization = webhookOrganization();
        TestBrowser admin = adminOf(organization);
        Hook hook = live(admin, receiver, EVENTS_CREATED);
        String replacement = JsonPath.read(rotateSecret(admin, hook, 24).body(), "$.data.secret");

        Response ended = admin.postJson(WEBHOOKS + "/" + hook.id() + "/secret/end-overlap", "{}");
        Received delivery = deliverOne(organization, admin, hook, "After the overlap");

        assertThat(ended.status()).as(ended.body()).isEqualTo(200);
        assertThat(WebhookSupport.<String>optional(ended.body(), "$.data.previousSecretUntil")).isNull();
        assertThat(WebhookReceiver.signatures(delivery)).isEqualTo(1);
        assertThat(WebhookReceiver.verify(delivery, replacement, 300)).isTrue();
        assertThat(WebhookReceiver.verify(delivery, hook.secret(), 300)).as("the old secret").isFalse();
    }

    @Test
    void aLeakedSecretIsReplacedWithNoOverlapAndNeverSignsAgain() {
        Organization organization = webhookOrganization();
        TestBrowser admin = adminOf(organization);
        Hook hook = live(admin, receiver, EVENTS_CREATED);

        String replacement = JsonPath.read(rotateSecret(admin, hook, 0).body(), "$.data.secret");
        Received delivery = deliverOne(organization, admin, hook, "After the leak");

        assertThat(WebhookReceiver.signatures(delivery)).isEqualTo(1);
        assertThat(WebhookReceiver.verify(delivery, replacement, 300)).isTrue();
        assertThat(WebhookReceiver.verify(delivery, hook.secret(), 300)).as("the leaked secret").isFalse();
        assertThat(secretNumber(admin, hook)).isEqualTo(2);
    }

    @Test
    void anOverlapLongerThanTheCeilingIsRefused() {
        Organization organization = webhookOrganization();
        TestBrowser admin = adminOf(organization);
        Hook hook = create(admin, receiver, EVENTS_CREATED);

        assertThat(rotateSecret(admin, hook, 25).status()).isEqualTo(400);
        assertThat(rotateSecret(admin, hook, -1).status()).isEqualTo(400);
        assertThat(secretNumber(admin, hook)).isEqualTo(1);
    }

    @Test
    void aSecondInstanceAgreesOnTheSecretAndSignsWithTheNewOne() {
        Organization organization = webhookOrganization();
        TestBrowser admin = adminOf(organization);
        Hook hook = live(admin, receiver, EVENTS_CREATED);
        try (TestApplication second = TestApplication.start("platform.webhooks.enabled=true",
                "platform.webhooks.worker.poll-interval=100ms")) {
            TestBrowser onSecond = TestOrganizations.signedIn(second.port(), organization.host(),
                    organization.admin().person());

            String replacement = JsonPath.read(onSecond.postJson(WEBHOOKS + "/" + hook.id() + "/secret/rotate",
                    "{\"overlapHours\":0}").body(), "$.data.secret");

            assertThat(secretNumber(admin, hook)).as("the first instance sees the second's rotation").isEqualTo(2);
            Received delivery = deliverOne(organization, admin, hook, "Signed by whichever instance claims it");
            assertThat(WebhookReceiver.verify(delivery, replacement, 300)).isTrue();
            assertThat(WebhookReceiver.verify(delivery, hook.secret(), 300)).isFalse();
        }
    }
}
