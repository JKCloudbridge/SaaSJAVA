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
 * Who a webhook may tell about what (Sprint 32, ADR-0277 item 3): a subscription acts as the member who made it, and a
 * delivery is judged at the moment it is made by the one rule of the platform. A record that member cannot read now is
 * not sent, however the event came about; one that becomes unreadable stops; a member who is deactivated stops the
 * subscription and delivers nothing; and the skip is counted and visible but names no record.
 */
@WebhookTest
class WebhookVisibilityIT extends WebhookSupport {

    private WebhookReceiver receiver;

    @BeforeEach
    void startReceiver() {
        receiver = new WebhookReceiver();
    }

    @AfterEach
    void stopReceiver() {
        receiver.close();
    }

    private static void makePrivate(TestBrowser admin) {
        Response current = admin.get("/api/v1/sharing/objects/Employee__c");
        boolean configured = JsonPath.read(current.body(), "$.data.configured");
        String version = configured ? ",\"version\":" + JsonPath.<Number>read(current.body(), "$.data.version") : "";
        Response set = admin.request("PUT", "/api/v1/sharing/objects/Employee__c",
                "{\"defaultAccess\":\"PRIVATE\",\"grantViaHierarchy\":false" + version + "}");
        assertThat(set.status()).as(set.body()).isEqualTo(200);
    }

    @Test
    void aSubscriptionIsToldOnlyAboutTheRecordsItsMemberCanReadAtTheMomentOfDelivery() {
        Organization organization = webhookOrganization();
        TestBrowser admin = adminOf(organization);
        Integrator who = memberWithAbilitiesAndMatrix(organization, admin,
                employeeMatrix("read", "create", "update", "delete"), "webhooks.view", "webhooks.manage");
        TestBrowser member = as(organization, who.member());
        Caller memberProgram = caller(organization.host(), member);
        Caller adminProgram = administrator(organization);
        Hook hook = live(member, receiver, EVENTS_CREATED);
        makePrivate(admin);

        String hidden = createdEmployee(adminProgram, "Hidden from the member");
        String own = createdEmployee(memberProgram, "Made by the member");
        await("the member's own record", () -> receiver.of("record.created").size() >= 1);
        await("the hidden one to be set aside", () -> count(member, hook, "SKIPPED") >= 1);

        assertThat(receiver.of("record.created")).hasSize(1);
        assertThat(JsonPath.<String>read(receiver.of("record.created").get(0).text(), "$.recordId")).isEqualTo(own);
        assertThat(receiver.received().toString()).doesNotContain(hidden);
        Response skipped = deliveries(member, hook, "SKIPPED");
        assertThat(JsonPath.<String>read(skipped.body(), "$.data.items[0].reason")).isEqualTo("not_visible");
        assertThat(skipped.body()).as("the log never names a record the member cannot read").doesNotContain(hidden);
        assertThat(JsonPath.<String>read(skipped.body(), "$.data.items[0].recordId")).isNull();
        assertThat(JsonPath.<Integer>read(read(member, hook).body(), "$.data.queue.skipped")).isGreaterThanOrEqualTo(1);
    }

    @Test
    void aRecordThatBecomesUnreadableStopsBeingToldAbout() {
        Organization organization = webhookOrganization();
        TestBrowser admin = adminOf(organization);
        Integrator who = memberWithAbilitiesAndMatrix(organization, admin,
                employeeMatrix("read", "create", "update", "delete"), "webhooks.view", "webhooks.manage");
        TestBrowser member = as(organization, who.member());
        Hook hook = live(member, receiver, EVENTS_CREATED);
        Caller adminProgram = administrator(organization);

        createdEmployee(adminProgram, "Readable now");
        await("the first delivery", () -> receiver.of("record.created").size() == 1);
        setMatrix(admin, who, "{\"objects\":[],\"fields\":[]}");
        createdEmployee(adminProgram, "Not readable now");
        await("the second to be set aside", () -> count(member, hook, "SKIPPED") >= 1);

        assertThat(receiver.of("record.created")).hasSize(1);
        assertThat(JsonPath.<String>read(deliveries(member, hook, "SKIPPED").body(), "$.data.items[0].reason"))
                .isEqualTo("not_visible");
    }

    @Test
    void aMemberWhoIsDeactivatedStopsTheSubscriptionAndNothingIsDelivered() {
        Organization organization = webhookOrganization();
        TestBrowser admin = adminOf(organization);
        Integrator who = memberWithAbilitiesAndMatrix(organization, admin,
                employeeMatrix("read", "create", "update", "delete"), "webhooks.view", "webhooks.manage");
        TestBrowser member = as(organization, who.member());
        Hook hook = live(member, receiver, EVENTS_CREATED);
        assertThat(JsonPath.<Boolean>read(read(admin, hook).body(), "$.data.running")).isTrue();

        Response deactivated = admin.postJson("/api/v1/members/" + who.member().membership() + "/deactivate", "{}");
        assertThat(deactivated.status()).isEqualTo(204);
        createdEmployee(administrator(organization), "After the member left");
        await("the delivery to be set aside", () -> count(admin, hook, "SKIPPED") >= 1);

        Response shown = read(admin, hook);
        assertThat(JsonPath.<Boolean>read(shown.body(), "$.data.running")).isFalse();
        assertThat(JsonPath.<String>read(shown.body(), "$.data.stoppedReason")).isEqualTo("member_not_active");
        assertThat(JsonPath.<String>read(shown.body(), "$.data.actsAs")).isNull();
        assertThat(JsonPath.<String>read(deliveries(admin, hook, "SKIPPED").body(), "$.data.items[0].reason"))
                .isEqualTo("member_inactive");
        assertThat(receiver.of("record.created")).isEmpty();
    }

    @Test
    void aSubscriptionLimitedToOneObjectIsNotToldAboutAnother() {
        Organization organization = webhookOrganization();
        TestBrowser admin = adminOf(organization);
        Hook hook = create(admin, hookName(), receiver.address("/limited"), EVENTS_CREATED, "[\"RecordType\"]");
        assertThat(JsonPath.<Boolean>read(sendTest(admin, hook).body(), "$.data.delivered")).isTrue();
        assertThat(switchOn(admin, hook).status()).isEqualTo(200);
        receiver.clear();

        createdEmployee(administrator(organization), "Not an object the hook listed");
        try {
            Thread.sleep(java.time.Duration.ofSeconds(2).toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }

        assertThat(receiver.received()).isEmpty();
        assertThat(JsonPath.<Integer>read(read(admin, hook).body(), "$.data.queue.pending")).isZero();
        assertThat(JsonPath.<Integer>read(read(admin, hook).body(), "$.data.queue.skipped")).isZero();
    }

    @Test
    void anObjectThatDoesNotExistCannotBeNamed() {
        Organization organization = webhookOrganization();
        TestBrowser admin = adminOf(organization);

        Response refused = admin.postJson(WEBHOOKS, saveBody(hookName(), receiver.address("/x"), EVENTS_CREATED,
                "[\"NoSuchObject__c\"]", 0));

        assertThat(refused.status()).isEqualTo(400);
        assertThat(JsonPath.<List<String>>read(refused.body(), "$.error.fields.objects")).isNotEmpty();
    }
}
