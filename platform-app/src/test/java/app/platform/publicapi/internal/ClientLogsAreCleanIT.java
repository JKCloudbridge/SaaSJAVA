package app.platform.publicapi.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.LogCapture;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestDatabase;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations.Organization;
import app.platform.testsupport.tenancy.TenantFixtures;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * No secret and no token of a connected application is logged, audited, counted, echoed or stored where it does not
 * belong (Sprint 31, ADR-0012, ADR-0268, ADR-0272): canaries are put into the secret that the platform makes, the token
 * it gives, a wrong secret, the Basic header, and the words typed when registering, then taken through every call of
 * the management API and of the token endpoint with the application and the frameworks logging at debug level. Then
 * everything that could carry a trace is searched: the log file, the audit trail, every answer, the metrics, and every
 * table of the database. The typed name is allowed in one place only, its own table; a secret and a token in none (a
 * hash is a hash, not the value). A positive control proves the search can find what it looks for.
 */
@PlatformIntegrationTest
@TestPropertySource(properties = {
    "logging.file.name=" + ClientLogsAreCleanIT.LOG_FILE,
    "logging.level.app.platform=DEBUG",
    "logging.level.org.springframework.security=DEBUG",
    "logging.level.org.springframework.web=DEBUG",
    "logging.level.org.springframework.jdbc=DEBUG"})
class ClientLogsAreCleanIT extends ClientSupport {

    static final String LOG_FILE = "target/it-logs/client-flows-clean.log";

    @Autowired
    private MeterRegistry meters;

    @BeforeAll
    static void startWithAnEmptyLogFile() throws IOException {
        Files.deleteIfExists(Path.of(LOG_FILE));
    }

    private static String typed(String what) {
        return what + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 14);
    }

    private static List<String> tables() throws SQLException {
        try (Connection connection = TestDatabase.ownerConnection();
                var statement = connection.prepareStatement("select table_name from information_schema.tables "
                        + "where table_schema = current_schema() and table_type = 'BASE TABLE' order by table_name");
                var rows = statement.executeQuery()) {
            List<String> found = new ArrayList<>();
            while (rows.next()) {
                found.add(rows.getString(1));
            }
            return found;
        }
    }

    private static long hits(Connection connection, String table, String needle) throws SQLException {
        return TenantFixtures.count(connection, "select count(*) from " + table
                + " t where strpos(lower(cast(t as text)), ?) > 0", needle.toLowerCase(Locale.ROOT));
    }

    @Test
    void noSecretTokenOrTypedWordLeaksIntoALogAnAuditRecordAnAnswerAMetricOrAnyOtherTable() throws Exception {
        Organization organization = organizationWithClients();
        TestBrowser admin = adminOf(organization);
        String name = typed("name");
        String description = typed("description");
        String wrong = "cs_" + typed("wrong").replace("-", "");
        List<Response> answers = new ArrayList<>();

        Response created = admin.postJson(CLIENTS, "{\"name\":\"" + name + "\",\"description\":\"" + description
                + "\",\"scopes\":" + ALL_SCOPES + "}");
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        String id = com.jayway.jsonpath.JsonPath.read(created.body(), "$.data.detail.app.id");
        String clientId = com.jayway.jsonpath.JsonPath.read(created.body(), "$.data.detail.app.clientId");
        String first = com.jayway.jsonpath.JsonPath.read(created.body(), "$.data.clientSecret");
        String basic = Base64.getEncoder().encodeToString((clientId + ":" + first).getBytes(StandardCharsets.UTF_8));

        Response token = tokenRequest(organization.host(), clientId, first, null);
        String accessToken = accessToken(token);
        Caller program = new Caller(port, organization.host(), accessToken, null);
        answers.add(program.get(OBJECTS));
        answers.add(program.post(EMPLOYEE_RECORDS, createBody("{\"name__c\":\"" + typed("value") + "\"}")));
        answers.add(program.get("/api/v1/auth/me"));
        answers.add(tokenRequest(organization.host(), clientId, wrong, null));
        answers.add(tokenRequest(organization.host(), "ca_" + "q".repeat(24), wrong, null));
        answers.add(admin.get(CLIENTS));
        answers.add(read(admin, id));
        answers.add(admin.get(CLIENTS + "/catalogue"));
        Response rotation = rotate(admin, id, 1, false);
        String second = newSecretOf(rotation);
        answers.add(admin.request("PUT", CLIENTS + "/" + id, "{\"name\":\"" + name + "\",\"description\":\"\","
                + "\"scopes\":[\"records.read\"],\"tokenMinutes\":15,\"version\":" + versionOfApplication(admin, id)
                + "}"));
        answers.add(disable(admin, id));
        answers.add(tokenRequest(organization.host(), clientId, second, null));
        answers.add(enable(admin, id));
        answers.add(revokeSecret(admin, id, secretIds(admin, id).get(0)));
        answers.add(removeApplication(admin, id));
        String trail = admin.get("/api/v1/audit-events?limit=200").body();
        String log = Files.readString(Path.of(LOG_FILE));

        List<String> secrets = List.of(first, second, accessToken, wrong, basic);
        for (String secret : secrets) {
            assertThat(log).as("the log: " + secret.substring(0, 6)).doesNotContain(secret);
            assertThat(trail).as("the audit trail").doesNotContain(secret);
            assertThat(owner("select attributes::text from audit_record where attributes::text like ?",
                    "%" + secret + "%")).as("the audit records").isEmpty();
            assertThat(owner("select payload::text from outbox_event where payload::text like ?", "%" + secret + "%"))
                    .as("the events").isEmpty();
            for (Meter meter : meters.getMeters()) {
                meter.getId().getTags().forEach(tag -> assertThat(tag.getValue()).as("a metric tag")
                        .doesNotContain(secret));
            }
            for (Response answer : answers) {
                if (answer != rotation) {
                    assertThat(answer.body()).as("an answer").doesNotContain(second);
                }
                assertThat(answer.body()).as("an answer").doesNotContain(accessToken).doesNotContain(wrong)
                        .doesNotContain(basic);
            }
        }
        assertThat(created.body()).as("the secret is in the answer that made it, once").contains(first);
        assertThat(rotation.body()).contains(second).doesNotContain(first);
        for (String typedWords : List.of(name, description)) {
            assertThat(log).as("the log: typed words").doesNotContain(typedWords);
            assertThat(trail).as("the audit trail: typed words").doesNotContain(typedWords);
            assertThat(owner("select attributes::text from audit_record where attributes::text like ?",
                    "%" + typedWords + "%")).as("the audit records: typed words").isEmpty();
        }

        // Every table of the database, as the owner: the secrets and the token in none (a hash is not the value), the
        // typed name in its own table only.
        try (Connection connection = TestDatabase.ownerConnection()) {
            for (String table : tables()) {
                for (String secret : secrets) {
                    assertThat(hits(connection, table, secret)).as(table + " holds a secret").isZero();
                }
                assertThat(hits(connection, table, name)).as(table + " holds the typed name")
                        .isEqualTo(table.equals("oauth_client") ? hits(connection, table, name) : 0);
            }
            assertThat(hits(connection, "oauth_client", name)).as("the positive control: it is found where it is")
                    .isEqualTo(1);
        }
    }

    @Test
    void theMetricsOfConnectedApplicationsCountResultsAndCausesAndNothingElse() {
        Organization organization = organizationWithClients();
        Registered app = register(adminOf(organization), ALL_SCOPES);
        tokenRequest(organization.host(), app.clientId(), app.secret(), null);
        tokenRequest(organization.host(), app.clientId(), "cs_" + "H".repeat(43), null);

        List<Meter> ours = meters.getMeters().stream()
                .filter(meter -> meter.getId().getName().startsWith("platform.clients.")).toList();

        assertThat(ours).isNotEmpty();
        for (Meter meter : ours) {
            assertThat(meter.getId().getTags().stream().map(tag -> tag.getKey()).toList())
                    .as(meter.getId().getName()).isSubsetOf("result", "reason", "environment", "application");
        }
        assertThat(meters.get("platform.clients.tokens").tag("result", "issued").counter().count())
                .isGreaterThanOrEqualTo(1);
        assertThat(meters.get("platform.clients.auth.failures").tag("reason", "wrong_secret").counter().count())
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    void theRequestLogLineNamesTheApplicationAndNeverItsTokenAndAPersonsLineNamesNone() {
        Organization organization = organizationWithClients();
        Registered app = register(adminOf(organization), ALL_SCOPES);
        Caller program = clientCaller(organization, app);
        try (LogCapture access = LogCapture.of("platform.http")) {
            program.get(OBJECTS);
            administrator(organization).get(OBJECTS);

            List<String> clients = access.events().stream()
                    .map(event -> String.valueOf(LogCapture.keyValue(event, "client_id"))).toList();
            assertThat(clients).contains(app.clientId()).contains("null");
            assertThat(clients.stream().filter(client -> !"null".equals(client))).containsOnly(app.clientId());
            access.events().forEach(event -> assertThat(event.getFormattedMessage() + event.getKeyValuePairs())
                    .doesNotContain(program.token()));
        }
    }
}
