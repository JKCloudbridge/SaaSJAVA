package app.platform.integration;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestDatabase;
import app.platform.testsupport.TestHttp.Response;
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
 * The leak test of the reliability of the flows (Sprint 29, ADR-0234): a record is sent, the service goes down
 * and comes back, a change is refused and set aside and sent again, the circuit is forced and the limits are
 * changed, with the application logging at debug level. A value of a record, an identifier the other system gave,
 * the address of the service and the credential are never logged, never written to the audit trail, never echoed
 * by the list of deliveries, the attempts, the circuit or the health, never put in a metric and never found in the
 * rows of the queue tables. The trail does show what was done, by identifiers and counts.
 */
@ReliabilityTest
@TestPropertySource(properties = {
    "logging.file.name=" + ReliabilityFlowsLogsAreCleanIT.LOG_FILE,
    "logging.level.app.platform=DEBUG",
    "logging.level.org.springframework.web=DEBUG"})
class ReliabilityFlowsLogsAreCleanIT extends ReliabilitySupport {

    static final String LOG_FILE = "target/it-logs/reliability-flows-clean.log";

    @Autowired
    private MeterRegistry meters;

    private FakeService service;

    @BeforeAll
    static void startWithAnEmptyLogFile() throws IOException {
        Files.deleteIfExists(Path.of(LOG_FILE));
    }

    @BeforeEach
    void startService() throws Exception {
        service = new FakeService();
    }

    @AfterEach
    void stopService() {
        service.close();
    }

    private static String canary(String what) {
        return what + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    /** Every row of the queue tables of one organization, as text, without the one column that exists to be asked. */
    private static String rowsOf(Linked linked) throws SQLException {
        StringBuilder rows = new StringBuilder();
        try (Connection owner = TestDatabase.ownerConnection()) {
            owner.setAutoCommit(false);
            // Row level security is forced even for the owner: the rows of one organization are seen in its context.
            TenantFixtures.setTenant(owner, linked.organization().id());
            Statement statement = owner.createStatement();
            for (String table : List.of("integration_delivery", "integration_attempt", "integration_flow_state")) {
                String remove = table.equals("integration_delivery") ? " - 'remote_ref'" : "";
                try (ResultSet found = statement.executeQuery("select to_jsonb(t)" + remove + "::text from " + table
                        + " t where tenant_id = '" + linked.organization().id().value() + "'")) {
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
    void noValueNoIdentifierNoAddressAndNoSecretIsLoggedAuditedEchoedMeasuredOrStored() throws Exception {
        String external = canary("ext");
        service.creates("/api/item", external);
        Linked linked = linked(service, "EXTERNAL_WINS");
        TestBrowser admin = linked.admin();
        String connection = linked.connection();
        String first = canary("record-value");
        String second = canary("record-value");
        String third = canary("record-value");
        List<Response> answers = new ArrayList<>();

        // all well, then an outage with a forced circuit and new limits, then recovery
        String record = created(admin, first);
        await(linked, "the first record to arrive", () -> count(admin, connection, "DONE") == 1);
        service.outage(FakeService.Outage.REFUSE);
        created(admin, second);
        await(linked, "the circuit to open", () -> "OPEN".equals(circuitState(admin, connection)));
        answers.add(admin.postJson(ROOT + "/" + connection + "/circuit", "{\"action\":\"force-open\"}"));
        answers.add(admin.request("PUT", ROOT + "/" + connection + "/limits",
                "{\"ratePerMinute\":3000,\"maxConcurrent\":3}"));
        answers.add(admin.postJson(ROOT + "/" + connection + "/circuit", "{\"action\":\"automatic\"}"));
        service.up();
        await(linked, "the second record to arrive", () -> count(admin, connection, "DONE") == 2);

        // a change the other side refuses is set aside, then mended and sent again
        service.answer("PUT /api/item/" + external + "-1", FakeService.Reply.json(400, "{}"));
        admin.request("PATCH", "/api/v1/objects/" + OBJECT + "/records/" + record,
                "{\"version\":0,\"fields\":{\"" + TITLE_FIELD + "\":\"" + third + "\"}}");
        await(linked, "the refusal to set the change aside", () -> count(admin, connection, "DEAD_LETTERED") == 1);
        service.answer("PUT /api/item/" + external + "-1", FakeService.Reply.json(200, "{}"));
        String dead = JsonPath.read(deliveries(admin, connection, "DEAD_LETTERED").body(), "$.data[0].id");
        answers.add(admin.postJson(ROOT + "/" + connection + "/deliveries/" + dead + "/replay", "{}"));
        await(linked, "the replay to arrive", () -> count(admin, connection, "DONE") == 3);

        answers.add(deliveries(admin, connection));
        answers.add(admin.get(ROOT + "/" + connection + "/deliveries/" + dead + "/attempts"));
        answers.add(admin.get(ROOT + "/" + connection + "/circuit"));
        answers.add(health(admin, connection));
        answers.add(admin.get("/api/v1/integration-health"));
        String trail = admin.get("/api/v1/audit-events?limit=200").body();
        String log = Files.readString(Path.of(LOG_FILE));
        String tables = rowsOf(linked);
        String metrics = meters.getMeters().stream().map(meter -> meter.getId().toString())
                .collect(Collectors.joining("\n"));

        List<String> secrets = List.of(first, second, third, external, OUT_TOKEN, service.url(), "127.0.0.1:"
                + service.url().substring(service.url().lastIndexOf(':') + 1), "Idempotency-Key");
        for (String text : secrets) {
            assertThat(log).as("not logged: " + text).doesNotContain(text);
            assertThat(trail).as("not in the audit trail: " + text).doesNotContain(text);
            assertThat(metrics).as("not in a metric: " + text).doesNotContain(text);
            assertThat(tables).as("not in the queue tables: " + text).doesNotContain(text);
            for (Response answer : answers) {
                assertThat(answer.body()).as("not echoed: " + text).doesNotContain(text);
            }
        }
        assertThat(answers).extracting(Response::status).as("every call was answered").allMatch(status -> status < 500);
        assertThat(trail).contains("integration.circuit.forced").contains("integration.limits.changed")
                .contains("integration.delivery.dead_lettered").contains("integration.delivery.replayed");
        assertThat(metrics).contains("platform.integration.attempts").contains("platform.integration.circuit");
    }
}
