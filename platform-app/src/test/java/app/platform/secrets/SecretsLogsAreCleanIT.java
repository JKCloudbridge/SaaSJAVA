package app.platform.secrets;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.integration.FakeService;
import app.platform.sharedkernel.TenantId;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestDatabase;
import app.platform.testsupport.TestHttp;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations.Organization;
import app.platform.testsupport.tenancy.TenantFixtures;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.test.context.TestPropertySource;

/**
 * The secret-leak test of Sprint 27 (ADR-0012, ADR-0214). A canary secret is created, used by a real connection test
 * against a service the test starts, replaced, rolled back, refused in the ways a write can be wrong and removed, with
 * the application and the frameworks logging at debug level. Then everything that could carry it is searched, for the
 * canary and for the ciphertext that holds it: the log file, the audit trail, every answer of the API that touched a
 * credential or a connection, the metrics endpoint, the committed API description, and every table of the database
 * (those of the organization as the application role, the rest as the owner). The ciphertext is allowed in one place
 * only, its own table; the canary in none. A positive control proves the search can find what it looks for.
 */
@PlatformIntegrationTest
@TestPropertySource(properties = {
    "logging.file.name=" + SecretsLogsAreCleanIT.LOG_FILE,
    "logging.level.app.platform=DEBUG",
    "logging.level.org.springframework.security=DEBUG",
    "logging.level.org.springframework.web=DEBUG",
    "logging.level.org.springframework.jdbc=DEBUG"})
class SecretsLogsAreCleanIT extends CredentialSupport {

    static final String LOG_FILE = "target/it-logs/secrets-flows-clean.log";

    @LocalManagementPort
    private int managementPort;

    private FakeService service;

    @BeforeAll
    static void startWithAnEmptyLogFile() throws IOException {
        Files.deleteIfExists(Path.of(LOG_FILE));
    }

    @BeforeEach
    void startService() throws Exception {
        service = new FakeService();
        service.answer("/health", FakeService.Reply.status(200));
    }

    @AfterEach
    void stopService() {
        service.close();
    }

    private static String typed(String what) {
        return what + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private static List<String> ciphertexts(TenantId tenant) throws SQLException {
        return TenantFixtures.asTenant(tenant, connection -> {
            List<String> found = new ArrayList<>();
            try (var statement = connection.prepareStatement("select encode(sealed_value, 'hex'), "
                    + "encode(wrapped_key, 'hex'), encode(sealed_value, 'base64'), encode(wrapped_key, 'base64') "
                    + "from secret_version"); var rows = statement.executeQuery()) {
                while (rows.next()) {
                    for (int column = 1; column <= 4; column++) {
                        found.add(rows.getString(column).replaceAll("\\s", ""));
                    }
                    found.add(rows.getString(1).substring(0, 32));
                    found.add(rows.getString(2).substring(0, 32));
                }
            }
            return found;
        });
    }

    /** Every table of the schema, with whether it belongs to an organization. */
    private static List<String[]> tables() throws SQLException {
        try (Connection connection = TestDatabase.ownerConnection();
                var statement = connection.prepareStatement("select c.table_name, "
                        + "bool_or(c.column_name = 'tenant_id') from information_schema.columns c join "
                        + "information_schema.tables t on t.table_name = c.table_name and t.table_schema = "
                        + "c.table_schema where c.table_schema = current_schema() and t.table_type = 'BASE TABLE' "
                        + "group by c.table_name order by c.table_name");
                var rows = statement.executeQuery()) {
            List<String[]> found = new ArrayList<>();
            while (rows.next()) {
                found.add(new String[] {rows.getString(1), String.valueOf(rows.getBoolean(2))});
            }
            return found;
        }
    }

    /** How many rows of the table hold any of the needles anywhere in their text. One scan per table. */
    private static long hits(Connection connection, String table, List<String> needles) throws SQLException {
        String test = "strpos(lower(cast(t as text)), ?) > 0";
        String where = String.join(" or ", needles.stream().map(needle -> test).toList());
        return TenantFixtures.count(connection, "select count(*) from " + table + " t where " + where,
                needles.stream().map(needle -> needle.toLowerCase(Locale.ROOT)).toArray());
    }

    @Test
    void neitherTheCanaryNorItsCiphertextIsAnywhereButInsideItsOwnTable() throws Exception {
        Organization organization = organization();
        TestBrowser admin = adminOf(organization);
        String name = typed("cred");
        String link = typed("link").replace("-", "").substring(0, 12);
        String one = typed("canary-one");
        String two = typed("canary-two");
        String three = typed("canary-three");
        List<Response> answers = new ArrayList<>();

        answers.add(write(admin, name, one));
        answers.add(create(admin, signedIn(link, service.url(), name)));
        answers.add(test(admin, link));
        aMomentLater();
        answers.add(write(admin, name, two));
        answers.add(test(admin, link));
        aMomentLater();
        answers.add(write(admin, name, three));
        answers.add(rollBack(admin, name));
        answers.add(test(admin, link));
        answers.add(credentials(admin));
        answers.add(write(admin, "Bad Name", typed("canary-refused")));
        answers.add(write(admin, name, ""));
        answers.add(rollBack(admin, "missing-" + typed("x")));
        answers.add(admin.get(ROOT));
        answers.add(admin.get(ROOT + "/" + link));
        assertThat(service.seen()).as("the canary really was used and sent").hasSize(2);
        assertThat(service.seen().get(1).authorization()).isEqualTo("Bearer " + two);

        List<String> ciphertext = ciphertexts(organization.id());
        assertThat(ciphertext).as("the ciphertext of the retained versions exists").isNotEmpty();
        assertThat(removeCredential(admin, name).status()).isEqualTo(200);
        answers.add(admin.get(ROOT + "/" + link));

        List<String> canaries = List.of(one, two, three);
        String trail = admin.get("/api/v1/audit-events?limit=200").body();
        String log = Files.readString(Path.of(LOG_FILE));
        String metrics = new TestHttp(managementPort).get("/actuator/prometheus").body();
        String described = Files.readString(Path.of("..", "platform-api-contract", "src", "main", "resources",
                "openapi", "platform-api-v1.json"));

        for (String canary : canaries) {
            assertThat(log).as("not logged").doesNotContain(canary);
            assertThat(trail).as("not in the audit trail").doesNotContain(canary);
            assertThat(metrics).as("not in the metrics").doesNotContain(canary);
            assertThat(described).as("not in the API description").doesNotContain(canary);
            answers.forEach(answer -> assertThat(answer.body()).as("not in an answer").doesNotContain(canary));
        }
        for (String bytes : ciphertext) {
            assertThat(log).as("no ciphertext in the log").doesNotContainIgnoringCase(bytes);
            assertThat(trail).as("no ciphertext in the trail").doesNotContainIgnoringCase(bytes);
            assertThat(metrics).as("no ciphertext in the metrics").doesNotContainIgnoringCase(bytes);
            answers.forEach(answer -> assertThat(answer.body()).as("no ciphertext in an answer")
                    .doesNotContainIgnoringCase(bytes));
        }
        assertThat(described).as("the description has no value to read back").doesNotContain("\"plaintext\"");
        assertThat(trail).as("the trail shows what happened, by identifiers")
                .contains("credentials.created", "credentials.rotated", "credentials.rolled_back",
                        "credentials.deleted", "credentials.first_used");
        assertThat(metrics).contains("platform_secrets_operations");

        // the whole database: the canary nowhere, the ciphertext nowhere but in its own table
        for (String[] table : tables()) {
            List<String> needles = new ArrayList<>(canaries);
            if (!"secret_version".equals(table[0])) {
                needles.addAll(ciphertext);
            }
            assertThat(hitsFor(organization.id(), table, needles)).as(table[0] + " holds a canary or ciphertext")
                    .isZero();
        }
    }

    @Test
    void theSearchCanFindWhatItLooksFor() throws Exception {
        Organization organization = organization();
        TestBrowser admin = adminOf(organization);
        String name = typed("cred");
        write(admin, name, typed("canary-control"));
        List<String> ciphertext = ciphertexts(organization.id());

        long inItsOwnTable = TenantFixtures.asTenant(organization.id(),
                connection -> hits(connection, "secret_version", List.of(ciphertext.get(4))));

        assertThat(inItsOwnTable).as("the ciphertext is found where it is").isEqualTo(1);
        assertThat(Files.readString(Path.of(LOG_FILE))).as("the log is being written").contains("credentials");
    }

    private static long hitsFor(TenantId tenant, String[] table, List<String> needles) throws SQLException {
        if (Boolean.parseBoolean(table[1])) {
            return TenantFixtures.asTenant(tenant, connection -> hits(connection, table[0], needles));
        }
        try (Connection connection = TestDatabase.ownerConnection()) {
            return hits(connection, table[0], needles);
        }
    }
}
