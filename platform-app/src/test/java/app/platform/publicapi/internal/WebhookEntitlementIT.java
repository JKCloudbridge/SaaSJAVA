package app.platform.publicapi.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.integration.WebhookReceiver;
import app.platform.licensing.Plans;
import app.platform.licensing.Subscriptions;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations.Organization;
import app.platform.testsupport.TestPlatform;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The plan feature is the third of the three separate mechanisms (Sprint 32, ADR-0274): webhooks and the usage page
 * belong to the feature {@code integrations}, apart from the licence and the permission. An organization without it is
 * answered in one sentence and nothing is sent; an organization that loses it stops being told, and what was waiting
 * is held, not lost.
 */
@WebhookTest
class WebhookEntitlementIT extends WebhookSupport {

    @Autowired
    private Plans entitlementPlans;

    @Autowired
    private Subscriptions entitlementSubscriptions;

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
    void anOrganizationWithoutTheFeatureCannotUseTheWebhooksOrTheUsagePage() {
        Organization organization = organizationWithoutTheFeature();
        TestBrowser admin = adminOf(organization);

        Response made = admin.postJson(WEBHOOKS, saveBody(hookName(), receiver.address("/h"), EVENTS_CREATED, null, 0));
        Response list = admin.get(WEBHOOKS);
        Response usage = admin.get("/api/v1/usage");

        for (Response refused : new Response[] {made, list, usage}) {
            assertThat(refused.status()).as(refused.body()).isEqualTo(409);
            assertThat(JsonPath.<String>read(refused.body(), "$.error.message")).contains("plan");
        }
        assertThat(receiver.requests()).isZero();
    }

    private void changePlan(Organization organization, String... features) {
        int admins = Math.max(3, TestPlatform.adminLicencesInUse(organization.id()));
        String key = TestPlatform.plan(entitlementPlans, 8, admins, features);
        entitlementSubscriptions.change(organization.id(),
                new app.platform.licensing.SubscriptionChange(key, null, null, null),
                app.platform.sharedkernel.ActorId.SYSTEM);
    }

    private void loseTheFeature(Organization organization) {
        changePlan(organization);
    }

    private void regainTheFeature(Organization organization) {
        changePlan(organization, FEATURE);
    }

    @Test
    void anOrganizationThatLosesTheFeatureIsNotToldAnythingUntilItHasItAgain() {
        Organization organization = webhookOrganization();
        TestBrowser admin = adminOf(organization);
        Hook hook = live(admin, receiver, EVENTS_CREATED);
        loseTheFeature(organization);
        int beforeTheChange = receiver.requests();

        createdEmployee(administrator(organization), "While the plan lacks the feature");
        try {
            Thread.sleep(2000);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }

        assertThat(receiver.requests()).as("nothing is sent for a plan without the feature").isEqualTo(beforeTheChange);
        assertThat(admin.get(WEBHOOKS).status()).isEqualTo(409);
        regainTheFeature(organization);
        assertThat(read(admin, hook).status()).isEqualTo(200);
    }
}
