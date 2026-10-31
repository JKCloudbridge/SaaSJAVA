package app.platform.integration;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestDatabase;
import app.platform.testsupport.TestFileServer;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.tenancy.TenantFixtures;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
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
 * The leak test of file flows (Sprint 29A, ADR-0240 to ADR-0243): a file is picked up and applied, a bad file is set
 * aside, a record leaves in a file, and a server shows another key, with the application logging at debug level. The
 * value of a
 * row, the identifier in a row, the name of a file, the password and the address of the server are never logged, never
 * written to the audit trail, never echoed by the health or the deliveries, never put in a metric and never found in
 * the rows of the tables of the polls, the files and the queue. The trail does show what was done, by identifiers and
 * counts.
 */
@FileFlowTest
@TestPropertySource(properties = {
    "logging.file.name=" + FileFlowsLogsAreCleanIT.LOG_FILE,
    "logging.level.app.platform=DEBUG",
    "logging.level.org.springframework.web=DEBUG"})
class FileFlowsLogsAreCleanIT extends FileSupport {

    static final String LOG_FILE = "target/it-logs/file-flows-clean.log";

    @Autowired
    private MeterRegistry meters;

    private TestFileServer server;

    @BeforeAll
    static void startWithAnEmptyLogFile() throws IOException {
        Files.deleteIfExists(Path.of(LOG_FILE));
    }

    @BeforeEach
    void start() throws Exception {
        server = TestFileServer.start();
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private static String canary(String what) {
        return what + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private static String rowsOf(Site site) throws SQLException {
        StringBuilder rows = new StringBuilder();
        try (Connection owner = TestDatabase.ownerConnection()) {
            owner.setAutoCommit(false);
            TenantFixtures.setTenant(owner, site.organization().id());
            Statement statement = owner.createStatement();
            for (String table : List.of("integration_poll", "integration_file", "integration_delivery",
                    "integration_attempt", "integration_flow_state")) {
                String remove = table.equals("integration_delivery") ? " - 'remote_ref'" : "";
                try (ResultSet found = statement.executeQuery("select to_jsonb(t)" + remove + "::text from " + table
                        + " t where tenant_id = '" + site.organization().id().value() + "'")) {
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
    void noRowNoNameNoPasswordAndNoAddressIsLoggedAuditedEchoedMeasuredOrStored() throws Exception {
        Site site = site(server, true, true, "csv");
        TestBrowser admin = site.admin();
        String connection = site.connection();
        String rowTitle = canary("row-value");
        String rowId = canary("row-id");
        String goodName = canary("good-file");
        String badName = canary("bad-file");
        String badValue = canary("bad-value");
        String sentTitle = canary("sent-value");
        String strandedTitle = canary("stranded-value");
        List<Response> answers = new ArrayList<>();

        // a file is applied, a bad file is set aside
        server.put("/inbox", goodName + ".csv", csv(rowId + "," + rowTitle + ",note"), Instant.now().minusSeconds(90));
        server.put("/inbox", badName + ".csv", "name,title\nx," + badValue + "\n", Instant.now().minusSeconds(80));
        lookNow(site);
        await(site.linked(), "the good file to be applied and the bad one set aside",
                () -> fileCount(site, "filesDone") == 1 && fileCount(site, "filesSetAside") == 1);

        // a record leaves in a file
        created(admin, sentTitle);
        await(site.linked(), "the record to leave", () -> count(admin, connection, "DONE") == 1);

        // a server that shows another key: the connection is stopped
        server.wrongHostKey();
        created(admin, strandedTitle);
        await(site.linked(), "the connection to be stopped", () -> "FAILING".equals(state(admin, connection)));

        answers.add(health(admin, connection));
        answers.add(deliveries(admin, connection));
        answers.add(admin.get(ROOT + "/" + connection));
        answers.add(admin.get("/api/v1/integration-health"));
        String trail = admin.get("/api/v1/audit-events?limit=200").body();
        String log = Files.readString(Path.of(LOG_FILE));
        String tables = rowsOf(site);
        String metrics = meters.getMeters().stream().map(meter -> meter.getId().toString())
                .collect(Collectors.joining("\n"));

        List<String> secrets = List.of(rowTitle, rowId, goodName, badName, badValue, sentTitle, strandedTitle,
                TestFileServer.PASSWORD, "127.0.0.1:" + server.port());
        for (String text : secrets) {
            assertThat(log).as("not logged: " + text).doesNotContain(text);
            assertThat(trail).as("not in the audit trail: " + text).doesNotContain(text);
            assertThat(metrics).as("not in a metric: " + text).doesNotContain(text);
            assertThat(tables).as("not in the tables: " + text).doesNotContain(text);
        }
        // What the address is may be shown to the people who set the connection up; nothing else may carry it.
        for (String text : List.of(rowTitle, rowId, goodName, badName, badValue, sentTitle, strandedTitle,
                TestFileServer.PASSWORD)) {
            for (Response answer : answers) {
                assertThat(answer.body()).as("not echoed: " + text).doesNotContain(text);
            }
        }
        assertThat(answers).extracting(Response::status).allMatch(status -> status < 500);
        assertThat(trail).contains("integration.file.processed").contains("integration.stopped");
        assertThat(metrics).contains("platform.integration.files").contains("platform.integration.stopped");
    }
}
