package app.platform.integration;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Dead letters and replay (Sprint 29, ADR-0231): a change whose attempts or patience ran out is set aside, visibly; an
 * administrator sends it again, two clicks make one send, a completed effect on the other side is not repeated, and a
 * bulk replay takes the oldest first and never more than it was told.
 */
@ReliabilityTest
class DeadLetterReplayIT extends ReliabilitySupport {

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
    void twoClicksMakeOneSendAndACompletedEffectIsNotRepeated() throws Exception {
        Linked linked = linked(service, "EXTERNAL_WINS");
        TestBrowser admin = linked.admin();
        String connection = linked.connection();
        String record = created(admin, newTitle());
        await(linked, "the create to arrive", () -> count(admin, connection, "DONE") == 1);

        // a change the other side refuses is set aside at once
        service.answer("PUT /api/item/ext-1", FakeService.Reply.json(400, "{}"));
        Response patched = admin.request("PATCH", "/api/v1/objects/" + OBJECT + "/records/" + record,
                "{\"version\":0,\"fields\":{\"" + TITLE_FIELD + "\":\"" + newTitle() + "\"}}");
        assertThat(patched.status()).as(patched.body()).isEqualTo(200);
        await(linked, "the refusal to set the change aside", () -> count(admin, connection, "DEAD_LETTERED") == 1);
        String deadId = JsonPath.read(deliveries(admin, connection, "DEAD_LETTERED").body(), "$.data[0].id");
        assertThat(JsonPath.<Boolean>read(deliveries(admin, connection, "DEAD_LETTERED").body(),
                "$.data[0].canReplay")).isTrue();

        // the other side is mended; two administrators click at the same moment
        service.answer("PUT /api/item/ext-1", FakeService.Reply.json(200, "{}"));
        ExecutorService clicks = Executors.newFixedThreadPool(2);
        try {
            List<Callable<Response>> both = List.of(
                    () -> admin.postJson(ROOT + "/" + connection + "/deliveries/" + deadId + "/replay", "{}"),
                    () -> admin.postJson(ROOT + "/" + connection + "/deliveries/" + deadId + "/replay", "{}"));
            List<Integer> statuses = new ArrayList<>();
            for (Future<Response> one : clicks.invokeAll(both)) {
                statuses.add(one.get().status());
            }
            assertThat(statuses).as("one send, one refusal").containsExactlyInAnyOrder(200, 409);
        } finally {
            clicks.shutdownNow();
        }

        await(linked, "the replayed change to be sent", () -> count(admin, connection, "DONE") == 2);
        assertThat(service.effectsOf("PUT", "/api/item/ext-1")).as("sent once").isEqualTo(1);
        assertThat(service.effectsOf("POST", "/api/item")).as("a completed effect is not repeated").isEqualTo(1);
        assertThat(count(admin, connection, "DEAD_LETTERED")).isZero();

        // the story keeps both generations
        var story = attemptsOf(admin, connection, deadId);
        assertThat(story).extracting(attempt -> attempt.get("generation")).containsExactly(1, 2);
        assertThat(story.getFirst()).containsEntry("outcome", "PERMANENT");
        assertThat(story.getLast()).containsEntry("outcome", "SUCCESS");
        assertThat(JsonPath.<java.util.List<Integer>>read(deliveries(admin, connection).body(),
                "$.data[?(@.id == '" + deadId + "')].generation").getFirst()).isEqualTo(2);
        assertThat(admin.postJson(ROOT + "/" + connection + "/deliveries/" + deadId + "/replay", "{}").status())
                .as("a delivery that is not set aside cannot be sent again").isEqualTo(409);
    }

    @Test
    void aBulkReplayTakesTheOldestFirstAndNeverMoreThanItWasTold() throws Exception {
        service.answer("POST /api/item", FakeService.Reply.json(400, "{}"));
        Linked linked = linked(service, "EXTERNAL_WINS");
        TestBrowser admin = linked.admin();
        String connection = linked.connection();
        for (int i = 0; i < 3; i++) {
            created(admin, newTitle());
        }
        await(linked, "three changes to be set aside", () -> count(admin, connection, "DEAD_LETTERED") == 3);
        service.answer("POST /api/item", FakeService.Reply.json(201, "{}"));

        Response first = admin.postJson(ROOT + "/" + connection + "/deliveries/replay", "{\"limit\":2}");
        assertThat(first.status()).as(first.body()).isEqualTo(200);
        assertThat(JsonPath.<Integer>read(first.body(), "$.data.replayed")).isEqualTo(2);
        assertThat(JsonPath.<Integer>read(first.body(), "$.data.remaining")).isEqualTo(1);
        await(linked, "two to arrive", () -> count(admin, connection, "DONE") == 2);
        assertThat(count(admin, connection, "DEAD_LETTERED")).isEqualTo(1);

        Response rest = admin.postJson(ROOT + "/" + connection + "/deliveries/replay", "{}");
        assertThat(JsonPath.<Integer>read(rest.body(), "$.data.replayed")).isEqualTo(1);
        await(linked, "all three to arrive", () -> count(admin, connection, "DONE") == 3);
        assertThat(service.effectsOf("POST", "/api/item")).isEqualTo(3);
    }
}
