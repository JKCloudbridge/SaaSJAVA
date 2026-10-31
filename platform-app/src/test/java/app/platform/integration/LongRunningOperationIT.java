package app.platform.integration;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import com.jayway.jsonpath.JsonPath;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Long-running operations on the other side (Sprint 29, ADR-0233): a service that answers "accepted, ask later"
 * is asked about the job until it says it is done, the job is started once, the entity it made is linked, and a
 * job that fails is set aside with its own reason.
 */
@ReliabilityTest
class LongRunningOperationIT extends ReliabilitySupport {

    private FakeService service;

    @BeforeEach
    void startService() throws Exception {
        service = new FakeService();
    }

    @AfterEach
    void stopService() {
        service.close();
    }

    @Test
    void aJobIsAskedAboutUntilItIsDoneAndTheEntityItMadeIsLinked() throws Exception {
        service.answer("POST /api/item", FakeService.Reply.json(202, "{\"statusPath\":\"/jobs/1\"}"));
        service.answerInTurn("GET /jobs/1",
                FakeService.Reply.json(200, "{\"state\":\"running\"}"),
                FakeService.Reply.json(200, "{\"state\":\"running\"}"),
                FakeService.Reply.json(200, "{\"state\":\"done\",\"id\":\"ext-9\"}"));
        service.answer("PUT /api/item/ext-9", FakeService.Reply.json(200, "{}"));
        Linked linked = linked(service, "EXTERNAL_WINS");
        TestBrowser admin = linked.admin();
        String connection = linked.connection();

        String record = created(admin, newTitle());
        Set<String> states = new HashSet<>();
        await(linked, "the job to be done", () -> {
            states.addAll(statesOf(admin, connection));
            return count(admin, connection, "DONE") == 1;
        });

        assertThat(states).as("it waited for the other side").contains("WAITING_REMOTE");
        assertThat(service.asked("/api/item")).as("the job was started once").isEqualTo(1);
        assertThat(service.asked("/jobs/1")).isGreaterThanOrEqualTo(3);
        Response patched = admin.request("PATCH", "/api/v1/objects/" + OBJECT + "/records/" + record,
                "{\"version\":0,\"fields\":{\"" + TITLE_FIELD + "\":\"" + newTitle() + "\"}}");
        assertThat(patched.status()).as(patched.body()).isEqualTo(200);
        await(linked, "the change to go to the entity the job made", () -> service.asked("/api/item/ext-9") >= 1);
        assertThat(service.asked("/api/item")).as("a link exists, so no second create").isEqualTo(1);
        String everything = deliveries(admin, connection).body() + health(admin, connection).body();
        assertThat(everything).doesNotContain("/jobs/1").doesNotContain("ext-9").doesNotContain("statusPath");
    }

    @Test
    void aJobThatFailsIsSetAsideWithItsOwnReasonAndNeverStartedAgain() throws Exception {
        service.answer("POST /api/item", FakeService.Reply.json(202, "{\"statusPath\":\"/jobs/2\"}"));
        service.answer("GET /jobs/2", FakeService.Reply.json(200, "{\"state\":\"failed\"}"));
        Linked linked = linked(service, "EXTERNAL_WINS");
        TestBrowser admin = linked.admin();
        String connection = linked.connection();

        created(admin, newTitle());
        await(linked, "the job to fail", () -> count(admin, connection, "DEAD_LETTERED") == 1);

        assertThat(JsonPath.<String>read(deliveries(admin, connection, "DEAD_LETTERED").body(), "$.data[0].reason"))
                .isEqualTo("remote_failed");
        assertThat(service.asked("/api/item")).isEqualTo(1);
    }
}
