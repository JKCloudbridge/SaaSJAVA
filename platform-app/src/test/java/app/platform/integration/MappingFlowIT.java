package app.platform.integration;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;

/**
 * The exit test of Sprint 28 (ADR-0216 to ADR-0222): a record created in the platform appears in the simulated external
 * system with the mapped fields and the external identifier is kept; a signed change from that system updates the
 * record through the write pipeline; the link is right both ways; a change that came in is not sent back; a change the
 * platform makes goes out as a change of the same entity, never as a second entity.
 */
@PlatformIntegrationTest
@Import(TestSecrets.class)
class MappingFlowIT extends MappingSupport {

    private static final Duration PATIENCE = Duration.ofSeconds(180);

    private FakeService service;

    @BeforeEach
    void startService() throws Exception {
        service = new FakeService();
        service.answer("POST /api/item", FakeService.Reply.json(201, "{\"id\":\"ext-1\"}"));
        service.answer("PUT /api/item/ext-1", FakeService.Reply.json(200, "{\"id\":\"ext-1\"}"));
    }

    @AfterEach
    void stopService() {
        service.close();
    }

    private static String created(TestBrowser browser, String title) {
        Response made = browser.postJson("/api/v1/objects/" + OBJECT + "/records",
                "{\"fields\":{\"" + TITLE_FIELD + "\":\"" + title + "\"}}");
        assertThat(made.status()).as(made.body()).isEqualTo(201);
        return JsonPath.read(made.body(), "$.data.id");
    }

    private void awaitSent(String method, String path, int times) {
        Awaitility.await().atMost(PATIENCE).pollInterval(Duration.ofMillis(100)).until(
                () -> service.seen().stream().filter(one -> one.method().equals(method)
                        && one.path().equals(path)).count() >= times);
    }

    private static List<String> reasons(TestBrowser admin, String connection) {
        Response list = admin.get(ROOT + "/" + connection + "/deliveries");
        assertThat(list.status()).as(list.body()).isEqualTo(200);
        return JsonPath.read(list.body(), "$.data[*].reason");
    }

    @Test
    void aRecordCreatedHereAppearsThereAndASignedChangeFromThereUpdatesIt() throws Exception {
        Linked linked = linked(service, "EXTERNAL_WINS");
        TestBrowser admin = linked.admin();
        String canary = "title-" + UUID.randomUUID();

        // 1. outbound: the record appears at the other side, mapped, with the bearer credential, and is linked
        String record = created(admin, canary);
        awaitSent("POST", "/api/item", 1);
        var sent = service.seen().stream().filter(one -> one.method().equals("POST")).findFirst().orElseThrow();
        assertThat(sent.authorization()).isEqualTo("Bearer " + OUT_TOKEN);
        assertThat(sent.contentType()).isEqualTo("application/json");
        assertThat(JsonPath.<String>read(sent.body(), "$.title")).isEqualTo(canary);
        assertThat(sent.body()).as("only mapped fields leave").doesNotContain(NOTE_FIELD).doesNotContain(record);

        // 2. a later change of the record goes out as a change of the same entity
        Response patched = admin.request("PATCH", "/api/v1/objects/" + OBJECT + "/records/" + record,
                "{\"version\":0,\"fields\":{\"" + TITLE_FIELD + "\":\"" + canary + "-v2\"}}");
        assertThat(patched.status()).as(patched.body()).isEqualTo(200);
        awaitSent("PUT", "/api/item/ext-1", 1);
        assertThat(JsonPath.<String>read(service.bodies("/api/item/ext-1").getFirst(), "$.title"))
                .isEqualTo(canary + "-v2");
        assertThat(service.asked("/api/item")).as("one entity, one create").isEqualTo(1);

        // 3. inbound: a signed change for that entity updates the record, through the pipeline
        String body = "{\"id\":\"ext-1\",\"title\":\"from-outside\",\"note\":\"" + canary + "\"}";
        Response accepted = receiveSigned(linked.inboundId(), "item", body);
        assertThat(accepted.status()).as(accepted.body()).isEqualTo(200);
        assertThat(JsonPath.<String>read(accepted.body(), "$.data.result")).isEqualTo("accepted");
        Response read = admin.get("/api/v1/objects/" + OBJECT + "/records/" + record);
        assertThat(JsonPath.<String>read(read.body(), "$.data.fields." + TITLE_FIELD)).isEqualTo("from-outside");
        assertThat(JsonPath.<String>read(read.body(), "$.data.fields." + NOTE_FIELD)).isEqualTo(canary);

        // 4. no echo: the change that came in is not sent back to the connection that made it
        Awaitility.await().atMost(PATIENCE).pollInterval(Duration.ofMillis(200))
                .until(() -> reasons(admin, linked.connection()).contains("echo"));
        assertThat(service.asked("/api/item/ext-1")).as("nothing was sent back").isEqualTo(1);

        // 5. inbound for an entity the platform has no record for makes one, linked both ways
        String made = "{\"id\":\"ext-2\",\"title\":\"born-outside-" + canary + "\"}";
        assertThat(receiveSigned(linked.inboundId(), "item", made).status()).isEqualTo(200);
        List<String> ids = recordsTitled(admin, "born-outside-" + canary);
        assertThat(ids).hasSize(1);
        service.answer("PUT /api/item/ext-2", FakeService.Reply.json(200, "{\"id\":\"ext-2\"}"));
        Response again = admin.request("PATCH", "/api/v1/objects/" + OBJECT + "/records/" + ids.getFirst(),
                "{\"version\":1,\"fields\":{\"" + TITLE_FIELD + "\":\"changed-here\"}}");
        assertThat(again.status()).as(again.body()).isEqualTo(200);
        awaitSent("PUT", "/api/item/ext-2", 1);
        assertThat(service.asked("/api/item")).as("a link made by the receiver is used, no second create")
                .isEqualTo(1);

        // 6. the record of what happened carries facts only
        Response deliveries = admin.get(ROOT + "/" + linked.connection() + "/deliveries");
        assertThat(deliveries.body()).doesNotContain(canary).doesNotContain("ext-1").doesNotContain("ext-2")
                .doesNotContain(OUT_TOKEN).doesNotContain(SIGNING_SECRET);
    }

    @Test
    void aReplayedUnsignedWronglySignedOrStaleChangeIsRefusedAlikeAndChangesNothing() throws Exception {
        Linked linked = linked(service, "EXTERNAL_WINS");
        String record = created(linked.admin(), "kept");
        awaitSent("POST", "/api/item", 1);
        String body = "{\"id\":\"ext-1\",\"title\":\"hostile\"}";
        long now = System.currentTimeMillis() / 1000;
        String nonce = "n-" + UUID.randomUUID().toString().replace("-", "");

        String[] good = signed(SIGNING_SECRET, Long.toString(now), nonce, "item", body);
        assertThat(receive(linked.inboundId(), "item", body, good).status()).isEqualTo(200);

        Response replay = receive(linked.inboundId(), "item", body, good);
        Response unsigned = receive(linked.inboundId(), "item", body);
        Response wrong = receive(linked.inboundId(), "item", body, signed("not-the-secret-12345", Long.toString(now),
                "n-" + UUID.randomUUID().toString().replace("-", ""), "item", body));
        Response stale = receive(linked.inboundId(), "item", body, signed(SIGNING_SECRET, Long.toString(now - 3600),
                "n-" + UUID.randomUUID().toString().replace("-", ""), "item", body));
        Response altered = receive(linked.inboundId(), "item", body.replace("hostile", "worse"), signed(SIGNING_SECRET,
                Long.toString(now), "n-" + UUID.randomUUID().toString().replace("-", ""), "item", body));
        Response otherEntity = receive(linked.inboundId(), "other", body, signed(SIGNING_SECRET, Long.toString(now),
                "n-" + UUID.randomUUID().toString().replace("-", ""), "item", body));
        Response unknown = receive("0".repeat(32), "item", body, signed(SIGNING_SECRET, Long.toString(now),
                "n-" + UUID.randomUUID().toString().replace("-", ""), "item", body));
        Response malformed = receive("not-an-identity", "item", body);

        for (Response refused : List.of(replay, unsigned, wrong, stale, altered, otherEntity, unknown, malformed)) {
            assertThat(refused.status()).as(refused.body()).isEqualTo(401);
            assertThat(JsonPath.<String>read(refused.body(), "$.error.code")).isEqualTo("UNAUTHENTICATED");
            assertThat(JsonPath.<String>read(refused.body(), "$.error.message"))
                    .isEqualTo(JsonPath.<String>read(replay.body(), "$.error.message"));
        }
        Response read = linked.admin().get("/api/v1/objects/" + OBJECT + "/records/" + record);
        assertThat(JsonPath.<String>read(read.body(), "$.data.fields." + TITLE_FIELD)).isEqualTo("hostile");
    }

    @Test
    void aConnectionThatIsSwitchedOffAnswersLikeOneThatDoesNotExist() throws Exception {
        Linked linked = linked(service, "EXTERNAL_WINS");
        String body = "{\"id\":\"ext-9\",\"title\":\"x\"}";
        assertThat(disable(linked.admin(), linked.connection()).status()).isEqualTo(200);

        Response off = receiveSigned(linked.inboundId(), "item", body);
        Response none = receiveSigned("1".repeat(32), "item", body);

        assertThat(off.status()).isEqualTo(401);
        assertThat(none.status()).isEqualTo(401);
        assertThat(recordsTitled(linked.admin(), "x")).isEmpty();
    }

    @Test
    void theHostNeverNamesTheOrganizationOfAChange() throws Exception {
        Linked linked = linked(service, "EXTERNAL_WINS");
        var other = organization();
        String body = "{\"id\":\"ext-7\",\"title\":\"stays-in-its-own-organization\"}";
        String[] headers = signed(SIGNING_SECRET, Long.toString(System.currentTimeMillis() / 1000),
                "n-" + UUID.randomUUID().toString().replace("-", ""), "item", body);

        Response onTheOtherHost = new TestBrowser(port, other.host()).request("POST",
                "/api/v1/inbound/" + linked.inboundId() + "/item", body, headers);

        assertThat(onTheOtherHost.status()).as(onTheOtherHost.body()).isEqualTo(200);
        assertThat(recordsTitled(linked.admin(), "stays-in-its-own-organization")).hasSize(1);
        assertThat(recordsTitled(adminOf(other), "stays-in-its-own-organization")).isEmpty();
    }

    @Test
    void aBodyOverTheLimitIsRefusedBeforeItIsRead() throws Exception {
        Linked linked = linked(service, "EXTERNAL_WINS");
        String huge = "{\"id\":\"ext-8\",\"title\":\"" + "a".repeat(300_000) + "\"}";

        Response refused = receive(linked.inboundId(), "item", huge);

        assertThat(refused.status()).isEqualTo(413);
    }
}
