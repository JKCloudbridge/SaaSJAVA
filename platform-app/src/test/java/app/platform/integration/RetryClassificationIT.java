package app.platform.integration;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.TestBrowser;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Which failures are tried again and which never are (Sprint 29, ADR-0227, ADR-0228), and what a retry may not do: a
 * refusal of the record never retries, a temporary refusal does, a wait the other side asked for is honoured, and an
 * answer that was lost after the effect happened does not make a second entity.
 */
@ReliabilityTest
class RetryClassificationIT extends ReliabilitySupport {

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

    private static String firstDelivery(TestBrowser admin, String connection) {
        return JsonPath.read(deliveries(admin, connection).body(), "$.data[0].id");
    }

    @Test
    void aRefusalOfTheRecordIsNeverRetriedAndLeavesTheCircuitClosed() throws Exception {
        service.answer("POST /api/item", FakeService.Reply.json(400, "{}"));
        Linked linked = linked(service, "EXTERNAL_WINS");
        TestBrowser admin = linked.admin();

        created(admin, newTitle());
        await(linked, "the refusal to set the change aside", () -> count(admin, linked.connection(),
                "DEAD_LETTERED") == 1);

        var story = attemptsOf(admin, linked.connection(), firstDelivery(admin, linked.connection()));
        assertThat(story).hasSize(1);
        assertThat(story.getFirst()).containsEntry("outcome", "PERMANENT").containsEntry("reason", "rejected")
                .containsEntry("statusClass", "4xx");
        assertThat(service.asked("/api/item")).as("asked once and never again").isEqualTo(1);
        Thread.sleep(1_500);
        assertThat(service.asked("/api/item")).isEqualTo(1);
        assertThat(circuitState(admin, linked.connection())).as("an answer, even a refusal, shows it is alive")
                .isEqualTo("CLOSED");
    }

    @Test
    void aTemporaryRefusalIsTriedAgainUntilItWorksWithAWaitBetween() throws Exception {
        service.answerInTurn("POST /api/item", FakeService.Reply.status(503), FakeService.Reply.status(503),
                FakeService.Reply.json(201, "{\"id\":\"ext-1\"}"));
        Linked linked = linked(service, "EXTERNAL_WINS");
        TestBrowser admin = linked.admin();

        created(admin, newTitle());
        await(linked, "the change to be sent", () -> count(admin, linked.connection(), "DONE") == 1);

        var story = attemptsOf(admin, linked.connection(), firstDelivery(admin, linked.connection()));
        assertThat(story).extracting(attempt -> attempt.get("outcome")).containsExactly("RETRY", "RETRY", "SUCCESS");
        assertThat(story.getFirst()).containsEntry("reason", "unavailable").containsEntry("statusClass", "5xx");
        Instant first = Instant.parse((String) story.get(0).get("startedAt"));
        Instant second = Instant.parse((String) story.get(1).get("startedAt"));
        Instant third = Instant.parse((String) story.get(2).get("startedAt"));
        assertThat(Duration.between(first, second)).as("a wait before the second attempt")
                .isGreaterThanOrEqualTo(Duration.ofMillis(250));
        assertThat(Duration.between(second, third)).as("a longer wait before the third")
                .isGreaterThan(Duration.between(first, second).minusMillis(100));
        assertThat(service.effectsOf("POST", "/api/item")).isEqualTo(1);
    }

    @Test
    void aWaitTheOtherSideAskedForIsHonoured() throws Exception {
        service.answerInTurn("POST /api/item",
                new FakeService.Reply(429, Map.of("Retry-After", "2"), 0),
                FakeService.Reply.json(201, "{\"id\":\"ext-1\"}"));
        Linked linked = linked(service, "EXTERNAL_WINS");
        TestBrowser admin = linked.admin();

        created(admin, newTitle());
        await(linked, "the change to be sent", () -> count(admin, linked.connection(), "DONE") == 1);

        var story = attemptsOf(admin, linked.connection(), firstDelivery(admin, linked.connection()));
        assertThat(story.getFirst()).containsEntry("reason", "throttled_by_service");
        Instant first = Instant.parse((String) story.get(0).get("startedAt"));
        Instant second = Instant.parse((String) story.get(1).get("startedAt"));
        assertThat(Duration.between(first, second)).as("the 2 seconds it asked for")
                .isGreaterThanOrEqualTo(Duration.ofMillis(1_900));
        assertThat(circuitState(admin, linked.connection())).as("a service that asks to slow down is alive")
                .isEqualTo("CLOSED");
    }

    @Test
    void anAnswerLostAfterTheEffectDoesNotMakeASecondEntity() throws Exception {
        Linked linked = linked(service, "EXTERNAL_WINS");
        TestBrowser admin = linked.admin();
        service.outage(FakeService.Outage.LOSE_ANSWER);

        created(admin, newTitle());
        await(linked, "the service to be asked twice", () -> service.asked("/api/item") >= 2);
        assertThat(service.effectsOf("POST", "/api/item")).as("it took effect once on the other side").isEqualTo(1);
        service.up();
        await(linked, "the change to end", () -> count(admin, linked.connection(), "DONE") == 1);

        assertThat(service.effectsOf("POST", "/api/item")).as("and still once").isEqualTo(1);
        Set<String> keys = service.seen().stream().filter(one -> one.method().equals("POST"))
                .map(one -> one.header("idempotency-key")).collect(Collectors.toSet());
        assertThat(keys).as("the same key on every attempt of one send").hasSize(1).doesNotContainNull();
        List<String> outcomes = attemptsOf(admin, linked.connection(), firstDelivery(admin, linked.connection()))
                .stream().map(attempt -> (String) attempt.get("outcome")).toList();
        assertThat(outcomes.getFirst()).isEqualTo("RETRY");
        assertThat(outcomes.getLast()).isEqualTo("SUCCESS");
    }
}
