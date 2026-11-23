package app.platform.publicapi.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.integration.WebhookReceiver;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestDatabase;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations.Organization;
import app.platform.testsupport.tenancy.TenantFixtures;
import com.jayway.jsonpath.JsonPath;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * The leak test of the webhooks (Sprint 32, ADR-0278): a subscription is made, verified and switched on, a record is
 * delivered, the receiver refuses and the delivery is set aside and sent again, the secret is replaced, with the
 * application logging at debug level. The address, the secret, a header value (the signature), a word the receiver
 * answered with and a value of the record are never logged, never written to the audit trail, never echoed by the
 * delivery log or the attempts, never put in a metric and never found in the rows of the webhook tables, except where
 * the address and the sealed secret legitimately live. The trail does show what was done, by identifiers.
 */
@WebhookTest
@TestPropertySource(properties = {
    "logging.file.name=" + WebhookLogsAreCleanIT.LOG_FILE,
    "logging.level.app.platform=DEBUG",
    "logging.level.org.springframework.web=DEBUG"})
class WebhookLogsAreCleanIT extends WebhookSupport {

    static final String LOG_FILE = "target/it-logs/webhook-flows-clean.log";

    @Autowired
    private MeterRegistry meters;

    private WebhookReceiver receiver;

    @BeforeAll
    static void startWithAnEmptyLogFile() throws IOException {
        Files.deleteIfExists(Path.of(LOG_FILE));
    }

    @BeforeEach
    void startReceiver() {
        receiver = new WebhookReceiver();
    }

    @AfterEach
    void stopReceiver() {
        receiver.close();
    }

    private static String canary(String what) {
        return what + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    /** Every row of the webhook tables of one organization as text, without the one column that exists to be asked. */
    private static String rowsOf(Organization organization) throws SQLException {
        StringBuilder rows = new StringBuilder();
        try (Connection owner = TestDatabase.ownerConnection()) {
            owner.setAutoCommit(false);
            // Row level security is forced even for the owner: the rows of one organization are seen in its context.
            TenantFixtures.setTenant(owner, organization.id());
            Statement statement = owner.createStatement();
            for (String table : List.of("webhook_subscription", "webhook_delivery", "webhook_attempt", "secret_entry",
                    "secret_version", "notification")) {
                String remove = table.equals("webhook_subscription") ? " - 'address'" : "";
                try (ResultSet found = statement.executeQuery("select to_jsonb(t)" + remove + "::text from " + table
                        + " t where tenant_id = '" + organization.id().value() + "'")) {
                    while (found.next()) {
                        rows.append(found.getString(1)).append('\n');
                    }
                }
            }
            statement.close();
            owner.commit();
        }
        return rows.toString();
    }

    @Test
    void noAddressSecretHeaderAnswerOrValueIsLoggedAuditedEchoedMeasuredOrStored() throws Exception {
        Organization organization = webhookOrganization();
        TestBrowser admin = adminOf(organization);
        String path = "/" + canary("path");
        String name = "name-" + UUID.randomUUID().toString().substring(0, 8);
        String answer = canary("answer-text");
        String value = canary("record-value");
        receiver.answerBody(answer);
        List<Response> answers = new ArrayList<>();

        Hook hook = create(admin, name, receiver.address(path), EVENTS_CREATED, null);
        assertThat(JsonPath.<Boolean>read(sendTest(admin, hook).body(), "$.data.delivered")).isTrue();
        assertThat(switchOn(admin, hook).status()).isEqualTo(200);
        createdEmployee(administrator(organization), value);
        await("the delivery to be done", () -> count(admin, hook, "DONE") == 1);
        String signature = receiver.of("record.created").get(0).header("Webhook-Signature");
        String timestamp = receiver.of("record.created").get(0).header("Webhook-Timestamp");

        receiver.respondWith(400);
        createdEmployee(administrator(organization), value + "-second");
        await("the refusal to set the delivery aside", () -> count(admin, hook, "DEAD_LETTERED") == 1);
        String dead = firstDelivery(admin, hook, "DEAD_LETTERED");
        receiver.respondWith(200);
        answers.add(admin.postJson(WEBHOOKS + "/" + hook.id() + "/deliveries/" + dead + "/replay", "{}"));
        await("the replay to arrive", () -> count(admin, hook, "DONE") == 2);
        Response rotated = rotateSecret(admin, hook, 1);
        String replacement = JsonPath.read(rotated.body(), "$.data.secret");

        answers.add(deliveries(admin, hook));
        answers.add(attemptsOf(admin, hook, dead));
        answers.add(admin.get("/api/v1/automation/health"));
        String trail = admin.get("/api/v1/audit-events?limit=200").body();
        String log = Files.readString(Path.of(LOG_FILE));
        String tables = rowsOf(organization);
        String metrics = meters.getMeters().stream().map(meter -> meter.getId().toString())
                .collect(Collectors.joining("\n"));

        List<String> secrets = List.of(hook.secret(), replacement, signature, answer, value, value + "-second",
                receiver.address(path), path);
        for (String leaked : secrets) {
            assertThat(log).as("log: " + shorten(leaked)).doesNotContain(leaked);
            assertThat(trail).as("trail: " + shorten(leaked)).doesNotContain(leaked);
            assertThat(metrics).as("metrics: " + shorten(leaked)).doesNotContain(leaked);
            assertThat(tables).as("tables: " + shorten(leaked)).doesNotContain(leaked);
            for (Response response : answers) {
                assertThat(response.body()).as("answer: " + shorten(leaked)).doesNotContain(leaked);
            }
        }
        assertThat(timestamp).isNotBlank();
        assertThat(log).as("the name chosen by the organization").doesNotContain(name);
        assertThat(trail).doesNotContain(name);
        assertThat(metrics).doesNotContain(name);
        // what the trail does show: identifiers and kinds
        assertThat(trail).contains("webhooks.created").contains("webhooks.tested").contains("webhooks.enabled")
                .contains("webhooks.replayed").contains("webhooks.secret_rotated").contains(hook.id());
        // no organization, application, member or webhook is a tag of a metric
        assertThat(metrics).doesNotContain(hook.id()).doesNotContain(organization.id().value().toString());
    }

    private static String shorten(String text) {
        return text.length() > 12 ? text.substring(0, 12) + "..." : text;
    }
}
