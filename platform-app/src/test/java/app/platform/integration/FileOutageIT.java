package app.platform.integration;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestFileServer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The exit test of Sprint 29A for an unreliable file server (ADR-0237, ADR-0242): the server goes down, records are
 * written and files wait in its inbox, the breaker opens, the sends and the looks wait and nothing fails or is lost;
 * the server comes back, the breaker closes, every row arrives once and every record leaves in one file. The same with
 * a server that accepts the connection and never answers, which the deadline ends.
 */
@FileFlowTest
class FileOutageIT extends FileSupport {

    private TestFileServer server;

    @BeforeEach
    void start() throws Exception {
        server = TestFileServer.start();
    }

    @AfterEach
    void stop() {
        server.close();
    }

    @Test
    void aServerThatIsDownOpensTheBreakerLosesNothingAndEveryRowAndRecordArrivesOnceWhenItIsBack() throws Exception {
        Site site = site(server, true, true, "csv");
        TestBrowser admin = site.admin();
        String connection = site.connection();
        server.down();

        List<String> titles = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            String title = "out-" + UUID.randomUUID();
            titles.add(title);
            created(admin, title);
        }
        List<String> inbound = new ArrayList<>();
        StringBuilder text = new StringBuilder("id,title,note\n");
        for (int i = 0; i < 5; i++) {
            String title = "in-" + UUID.randomUUID();
            inbound.add(title);
            text.append(row("r" + i, title)).append('\n');
        }
        server.put("/inbox", "late.csv", text.toString(), Instant.now().minusSeconds(60));

        // The sends fail, the looks fail, and the breaker opens.
        lookNow(site);
        await(site.linked(), "the breaker to open", () -> "OPEN".equals(circuitState(admin, connection)));
        lookNow(site);

        // While it is open nothing is tried, and nothing has failed for good: every change is still waiting.
        assertThat(count(admin, connection, "DEAD_LETTERED")).isZero();
        assertThat(count(admin, connection, "DONE")).isZero();
        assertThat(server.visibleNames("/inbox")).as("the file is still where it was").containsExactly("late.csv");
        assertThat(server.visibleNames("/outbox")).isEmpty();
        for (String title : inbound) {
            assertThat(recordsWithTitle(admin, title)).isZero();
        }

        // The server returns: the breaker closes, the queue drains, the file is applied.
        server.up();
        lookNow(site);
        await(site.linked(), "every record to leave in a file and the file to be applied",
                () -> count(admin, connection, "DONE") == titles.size() && fileCount(site, "filesDone") == 1);
        await(site.linked(), "the breaker to close", () -> "CLOSED".equals(circuitState(admin, connection)));

        assertThat(server.visibleNames("/outbox")).as("one file for each record").hasSize(titles.size());
        for (String title : titles) {
            assertThat(server.contents("/outbox").stream().filter(content -> content.contains(title)).count())
                    .as("each record left once").isEqualTo(1);
        }
        for (String title : inbound) {
            assertThat(recordsWithTitle(admin, title)).as("each row arrived once").isEqualTo(1);
        }
        assertThat(count(admin, connection, "DEAD_LETTERED")).isZero();
        assertThat(fileCount(site, "filesSetAside")).isZero();
    }

    @Test
    void aServerThatAcceptsAndSaysNothingIsEndedByTheDeadlineAndTheWorkWaits() throws Exception {
        Site site = site(server, true, true, "csv", java.util.Map.of("timeoutSeconds", "5"));
        TestBrowser admin = site.admin();
        String connection = site.connection();
        server.hang();

        String title = "out-" + UUID.randomUUID();
        created(admin, title);
        drop(server, "waiting.csv", csv(row("1", "in-" + UUID.randomUUID())));
        lookNow(site);

        await(site.linked(), "the attempts to end in a timeout", () ->
                "timed_out".equals(lastFailureReason(admin, connection)));
        assertThat(count(admin, connection, "DEAD_LETTERED")).isZero();
        assertThat(server.visibleNames("/inbox")).containsExactly("waiting.csv");

        server.up();
        await(site.linked(), "the record to leave", () -> count(admin, connection, "DONE") == 1);
        assertThat(server.visibleNames("/outbox")).hasSize(1);
    }

    private static String lastFailureReason(TestBrowser admin, String connection) {
        // The API leaves out what is null, so before the first failure the key is not there at all.
        try {
            return com.jayway.jsonpath.JsonPath.read(health(admin, connection).body(),
                    "$.data.circuit.lastFailureReason");
        } catch (com.jayway.jsonpath.PathNotFoundException none) {
            return null;
        }
    }
}
