package app.platform.integration;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The limit on how fast a connection sends (Sprint 29, ADR-0232): a throttled change waits and never fails, the sends
 * are spread by the bucket, and a limit above the platform's ceiling is refused.
 */
@ReliabilityTest
class RateLimitIT extends ReliabilitySupport {

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
    void aSlowLimitSpreadsTheSendsAndFailsNoneOfThem() throws Exception {
        Linked linked = linked(service, "EXTERNAL_WINS");
        TestBrowser admin = linked.admin();
        String connection = linked.connection();
        Response set = admin.request("PUT", ROOT + "/" + connection + "/limits",
                "{\"ratePerMinute\":60,\"maxConcurrent\":1}");
        assertThat(set.status()).as(set.body()).isEqualTo(200);
        assertThat(JsonPath.<Integer>read(set.body(), "$.data.ratePerMinute")).isEqualTo(60);
        assertThat(JsonPath.<Boolean>read(set.body(), "$.data.ratePerMinuteIsDefault")).isFalse();

        for (int i = 0; i < 4; i++) {
            created(admin, newTitle());
        }
        Set<String> seenWaiting = new HashSet<>();
        await(linked, "all four to be sent", () -> {
            List<String> waiting = JsonPath.read(deliveries(admin, connection, "PENDING").body(),
                    "$.data[*].waitingFor");
            seenWaiting.addAll(waiting);
            return count(admin, connection, "DONE") == 4;
        });

        assertThat(seenWaiting).as("a throttled change waits with its reason").contains("throttled");
        assertThat(count(admin, connection, "DEAD_LETTERED")).isZero();
        assertThat(count(admin, connection, "FAILED")).isZero();
        List<Instant> starts = new ArrayList<>();
        List<String> ids = JsonPath.read(deliveries(admin, connection).body(), "$.data[*].id");
        for (String id : ids) {
            attemptsOf(admin, connection, id).forEach(attempt -> starts.add(Instant.parse((String) attempt.get(
                    "startedAt"))));
        }
        starts.sort(Instant::compareTo);
        assertThat(Duration.between(starts.getFirst(), starts.getLast()))
                .as("one send a second after the first, four of them").isGreaterThanOrEqualTo(Duration.ofSeconds(2));
        assertThat(service.effectsOf("POST", "/api/item")).isEqualTo(4);
    }

    @Test
    void aLimitAboveThePlatformsCeilingIsRefusedWithTheCeilingInTheWords() throws Exception {
        Linked linked = linked(service, "EXTERNAL_WINS");

        Response refused = linked.admin().request("PUT", ROOT + "/" + linked.connection() + "/limits",
                "{\"ratePerMinute\":600000,\"maxConcurrent\":2}");

        assertThat(refused.status()).isEqualTo(400);
        assertThat(refused.body()).contains("at most 60000");
        Response health = health(linked.admin(), linked.connection());
        assertThat(JsonPath.<Boolean>read(health.body(), "$.data.limits.ratePerMinuteIsDefault")).isTrue();
    }
}
