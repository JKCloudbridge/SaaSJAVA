package app.platform.integration;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.awaitility.Awaitility;
import org.awaitility.core.ConditionTimeoutException;

/**
 * What the tests of Sprint 29 share: making records, reading the queue, the circuit and the attempts through the real
 * API, and waiting for a state with a message made of facts only. Nothing here reads a table: the tests see what an
 * administrator sees, so a field the API leaves out cannot be relied on by a test either.
 */
public abstract class ReliabilitySupport extends MappingSupport {

    protected static final Duration PATIENCE = Duration.ofSeconds(120);

    protected static String created(TestBrowser browser, String title) {
        Response made = browser.postJson("/api/v1/objects/" + OBJECT + "/records",
                "{\"fields\":{\"" + TITLE_FIELD + "\":\"" + title + "\"}}");
        assertThat(made.status()).as(made.body()).isEqualTo(201);
        return JsonPath.read(made.body(), "$.data.id");
    }

    protected static Response deliveries(TestBrowser admin, String connection, String... states) {
        StringBuilder query = new StringBuilder("?limit=100");
        for (String state : states) {
            query.append("&state=").append(state);
        }
        Response list = admin.get(ROOT + "/" + connection + "/deliveries" + query);
        assertThat(list.status()).as(list.body()).isEqualTo(200);
        return list;
    }

    protected static List<String> statesOf(TestBrowser admin, String connection) {
        return JsonPath.read(deliveries(admin, connection).body(), "$.data[?(@.direction == 'OUT')].state");
    }

    protected static long count(TestBrowser admin, String connection, String state) {
        return statesOf(admin, connection).stream().filter(state::equals).count();
    }

    protected static String circuitState(TestBrowser admin, String connection) {
        Response circuit = admin.get(ROOT + "/" + connection + "/circuit");
        assertThat(circuit.status()).as(circuit.body()).isEqualTo(200);
        return JsonPath.read(circuit.body(), "$.data.state");
    }

    protected static Response health(TestBrowser admin, String connection) {
        Response health = admin.get(ROOT + "/" + connection + "/health");
        assertThat(health.status()).as(health.body()).isEqualTo(200);
        return health;
    }

    protected static List<Map<String, Object>> attemptsOf(TestBrowser admin, String connection, String delivery) {
        Response attempts = admin.get(ROOT + "/" + connection + "/deliveries/" + delivery + "/attempts");
        assertThat(attempts.status()).as(attempts.body()).isEqualTo(200);
        return JsonPath.read(attempts.body(), "$.data");
    }

    /** Waits until the condition holds; a failure says what the queue looked like, in states and reasons only. */
    protected void await(Linked linked, String what, Supplier<Boolean> condition) {
        try {
            Awaitility.await(what).atMost(PATIENCE).pollInterval(Duration.ofMillis(150)).until(condition::get);
        } catch (ConditionTimeoutException timedOut) {
            throw new AssertionError("Timed out waiting for " + what + ". States: " + statesOf(linked.admin(),
                    linked.connection()) + ", circuit: " + circuitState(linked.admin(), linked.connection())
                    + ", health: " + health(linked.admin(), linked.connection()).body(), timedOut);
        }
    }

    protected static String newTitle() {
        return "title-" + UUID.randomUUID();
    }
}
