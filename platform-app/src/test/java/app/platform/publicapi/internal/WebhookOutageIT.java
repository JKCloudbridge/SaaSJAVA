package app.platform.publicapi.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.integration.WebhookReceiver;
import app.platform.sharedkernel.ActorId;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations.Organization;
import app.platform.testsupport.tenancy.TenantFixtures;
import com.jayway.jsonpath.JsonPath;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * What a long outage and a lost worker do (Sprint 32, ADR-0279): an instance that stops in the middle of a delivery
 * loses nothing, and the repeat carries the same delivery identifier; an address that fails for the whole period
 * without
 * one success is switched off by the platform with a notice for the administrators, and nothing is deleted; switching
 * it
 * on again needs a new verification.
 */
@WebhookTest
class WebhookOutageIT extends WebhookSupport {

    private WebhookReceiver receiver;

    @BeforeEach
    void startReceiver() {
        receiver = new WebhookReceiver();
    }

    @AfterEach
    void stopReceiver() {
        receiver.close();
    }

    private static void sql(Organization organization, String statement, Object... parameters) throws SQLException {
        TenantFixtures.asTenant(organization.id(), connection -> TenantFixtures.update(connection, statement,
                parameters));
    }

    @Test
    void aWorkerThatDiedInTheMiddleOfADeliveryLosesNothingAndTheRepeatCarriesTheSameIdentifier() throws SQLException {
        Organization organization = webhookOrganization();
        TestBrowser admin = adminOf(organization);
        Hook hook = live(admin, receiver, EVENTS_CREATED);
        String employee = createdEmployee(administrator(organization), "The record of the lost delivery");
        await("the ordinary delivery", () -> count(admin, hook, "DONE") == 1);
        receiver.clear();
        UUID delivery = UUID.randomUUID();
        // The state a killed instance leaves: sending, one attempt made, a lease that ended a minute ago.
        sql(organization, "insert into webhook_delivery (id, tenant_id, subscription_id, event_id, event_type, "
                + "object_api_name, record_id, created_by, updated_by) values (?, ?, ?, ?, 'record.created', "
                + "'Employee__c', ?, ?, ?)", delivery, organization.id().value(), UUID.fromString(hook.id()),
                UUID.randomUUID(), UUID.fromString(employee), ActorId.SYSTEM.value(), ActorId.SYSTEM.value());
        sql(organization, "update webhook_delivery set state = 'SENDING', attempts = 1, run_at = now() - interval "
                + "'2 minutes', locked_until = now() - interval '1 minute', last_attempt_at = now() - interval "
                + "'1 minute', version = version + 1, updated_by = ? where id = ?", ActorId.SYSTEM.value(), delivery);

        await("the lost delivery to be finished", () -> count(admin, hook, "DONE") == 2);

        assertThat(receiver.of("record.created")).as("sent once more, no more").hasSize(1);
        assertThat(receiver.of("record.created").get(0).id()).isEqualTo(delivery.toString());
        assertThat(receiver.of("record.created").get(0).header("Webhook-Attempt")).isEqualTo("2");
        Response attempts = attemptsOf(admin, hook, delivery.toString());
        assertThat(JsonPath.<List<String>>read(attempts.body(), "$.data[*].outcome")).containsExactly("INTERRUPTED",
                "SUCCESS");
    }

    @Test
    void anAddressThatFailsForTheWholePeriodIsSwitchedOffTheAdministratorsAreToldAndNothingIsDeleted()
            throws SQLException, InterruptedException {
        Organization organization = webhookOrganization();
        TestBrowser admin = adminOf(organization);
        Caller program = administrator(organization);
        Hook hook = live(admin, receiver, EVENTS_CREATED);
        receiver.respondWith(400);
        createdEmployee(program, "First failure");
        await("the first failure", () -> count(admin, hook, "DEAD_LETTERED") == 1);
        // Seven days of unbroken failure, without waiting seven days.
        sql(organization, "update webhook_subscription set failing_since = now() - interval '8 days', "
                + "version = version + 1, updated_by = ? where id = ?", ActorId.SYSTEM.value(),
                UUID.fromString(hook.id()));

        createdEmployee(program, "The failure that tips it over");
        await("the platform to switch it off",
                () -> "DISABLED".equals(JsonPath.read(read(admin, hook).body(), "$.data.state")));

        Response shown = read(admin, hook);
        assertThat(JsonPath.<String>read(shown.body(), "$.data.disabledReason")).isEqualTo("auto_disabled");
        assertThat(JsonPath.<Boolean>read(shown.body(), "$.data.running")).isFalse();
        assertThat(count(admin, hook, "DEAD_LETTERED")).as("nothing was deleted").isGreaterThanOrEqualTo(2);
        Response notices = admin.get("/api/v1/notifications");
        assertThat(JsonPath.<List<String>>read(notices.body(), "$.data[?(@.kind == 'WEBHOOK_STOPPED')].href"))
                .isNotEmpty().allMatch("/setup/webhooks"::equals);
        assertThat(notices.body()).as("the notice names no address and no webhook").doesNotContain("127.0.0.1");
        assertThat(admin.get("/api/v1/audit-events?limit=100").body()).contains("webhooks.auto_disabled");

        // switching on again is the administrator's act and needs the verification again
        receiver.respondWith(200);
        Thread.sleep(1100);
        assertThat(JsonPath.<Boolean>read(sendTest(admin, hook).body(), "$.data.delivered")).isTrue();
        assertThat(switchOn(admin, hook).status()).isEqualTo(200);
        assertThat(JsonPath.<String>read(read(admin, hook).body(), "$.data.state")).isEqualTo("ACTIVE");
    }

    @Test
    void anAddressThatIsDownForAWhileOpensTheCircuitAndTheQueueWaitsWithoutHammeringIt() {
        Organization organization = webhookOrganization();
        TestBrowser admin = adminOf(organization);
        Caller program = administrator(organization);
        Hook hook = live(admin, receiver, EVENTS_CREATED);
        receiver.outage(true);

        for (int i = 0; i < 4; i++) {
            createdEmployee(program, "During the outage " + i);
        }
        await("the circuit to open", () -> "OPEN".equals(JsonPath.read(read(admin, hook).body(), "$.data.circuit"))
                || "HALF_OPEN".equals(JsonPath.read(read(admin, hook).body(), "$.data.circuit")));
        receiver.outage(false);

        await("the queue to drain once the address is back", () -> count(admin, hook, "DONE") == 4);
        assertThat(count(admin, hook, "DEAD_LETTERED")).isZero();
        assertThat(receiver.of("record.created").stream().map(WebhookReceiver.Received::id).distinct().count())
                .as("every event arrived exactly once").isEqualTo(4);
    }
}
