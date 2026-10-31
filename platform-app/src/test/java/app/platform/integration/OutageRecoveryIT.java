package app.platform.integration;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.TestBrowser;
import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The exit test of Sprint 29 (ADR-0227 to ADR-0236): the external system goes down, records are written, the circuit
 * opens, the deliveries wait and none fails or is lost, the system comes back, the circuit half-opens and closes, every
 * record arrives exactly once on the other side (its own count and the links agree), and the log tells the story in
 * reasons and classes of status only.
 */
@ReliabilityTest
class OutageRecoveryIT extends ReliabilitySupport {

    private static final int RECORDS = 6;

    private FakeService service;

    @BeforeEach
    void startService() throws Exception {
        service = new FakeService();
        service.creates("/api/item", "ext");
    }

    @AfterEach
    void stopService() {
        service.close();
    }

    @Test
    void anOutageOpensTheCircuitLosesNothingAndEveryRecordArrivesOnceWhenTheServiceIsBack() throws Exception {
        Linked linked = linked(service, "EXTERNAL_WINS");
        TestBrowser admin = linked.admin();
        String connection = linked.connection();

        // 0. all well: one record is sent and linked
        created(admin, newTitle());
        await(linked, "the first record to arrive", () -> count(admin, connection, "DONE") == 1);
        assertThat(service.effectsOf("POST", "/api/item")).isEqualTo(1);
        assertThat(circuitState(admin, connection)).isEqualTo("CLOSED");

        // 1. the service goes down; records are written meanwhile
        service.outage(FakeService.Outage.REFUSE);
        List<String> titles = new ArrayList<>();
        for (int i = 0; i < RECORDS; i++) {
            String title = newTitle();
            titles.add(title);
            created(admin, title);
        }

        // 2. the circuit opens after a few failures in a row
        await(linked, "the circuit to open", () -> "OPEN".equals(circuitState(admin, connection)));

        // 3. while it is open nothing is sent: let what was in flight land, then count
        Thread.sleep(500);
        int askedWhenOpen = service.seen().size();
        Thread.sleep(1_000);
        assertThat(service.seen().size()).as("a circuit that is open sends nothing").isEqualTo(askedWhenOpen);

        // 4. nothing failed and nothing was lost: every change waits, with the reason it waits for
        String waiting = deliveries(admin, connection, "PENDING").body();
        List<String> waitingFor = JsonPath.read(waiting, "$.data[*].waitingFor");
        assertThat(waitingFor).as(waiting).isNotEmpty().allSatisfy(reason -> assertThat(reason)
                .isIn("circuit_open", "backoff"));
        assertThat(count(admin, connection, "DEAD_LETTERED")).isZero();
        assertThat(count(admin, connection, "FAILED")).isZero();
        assertThat(statesOf(admin, connection)).hasSize(RECORDS + 1);
        assertThat(service.effectsOf("POST", "/api/item")).as("nothing took effect while it was down").isEqualTo(1);
        assertThat(JsonPath.<String>read(health(admin, connection).body(), "$.data.circuit.lastFailureReason"))
                .isEqualTo("response_invalid");

        // 5. the service returns: the circuit half-opens on a probe, closes, and the queue drains
        service.up();
        await(linked, "every record to arrive", () -> count(admin, connection, "DONE") == RECORDS + 1);
        await(linked, "the circuit to close", () -> "CLOSED".equals(circuitState(admin, connection)));

        // 6. exactly once: the service's own count and the links agree, and no delivery is left over
        assertThat(service.effectsOf("POST", "/api/item")).isEqualTo(RECORDS + 1);
        assertThat(count(admin, connection, "DONE")).isEqualTo(RECORDS + 1);
        assertThat(count(admin, connection, "DEAD_LETTERED")).isZero();
        List<String> sentBodies = service.bodies("/api/item");
        for (String title : titles) {
            assertThat(sentBodies.stream().filter(body -> body.contains(title)).count())
                    .as("each record reached the service at least once").isGreaterThanOrEqualTo(1);
        }

        // 7. the log tells the story in reasons and classes of status, and holds no value, address or identifier
        String list = deliveries(admin, connection, "DONE").body();
        List<String> ids = JsonPath.read(list, "$.data[?(@.attempts > 1)].id");
        assertThat(ids).as("at least one delivery needed more than one attempt").isNotEmpty();
        List<Map<String, Object>> story = attemptsOf(admin, connection, ids.getFirst());
        assertThat(story.getFirst().get("outcome")).isEqualTo("RETRY");
        assertThat(story.getFirst().get("reason")).isEqualTo("response_invalid");
        assertThat(story.getFirst().get("statusClass")).isEqualTo("none");
        assertThat(story.getLast().get("outcome")).isEqualTo("SUCCESS");
        assertThat(story.getLast().get("statusClass")).isEqualTo("2xx");
        String everything = list + admin.get(ROOT + "/" + connection + "/deliveries/" + ids.getFirst()
                + "/attempts").body() + health(admin, connection).body();
        assertThat(everything).doesNotContain(service.url()).doesNotContain("127.0.0.1").doesNotContain("ext-")
                .doesNotContain(OUT_TOKEN);
        for (String title : titles) {
            assertThat(everything).doesNotContain(title);
        }
    }
}
