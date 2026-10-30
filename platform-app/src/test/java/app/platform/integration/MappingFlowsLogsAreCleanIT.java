package app.platform.integration;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import com.jayway.jsonpath.JsonPath;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/**
 * The leak test of mapping, sending and receiving (Sprint 28, ADR-0012, ADR-0220, ADR-0221): a record is sent, a change
 * is
 * received, and every kind of refusal is provoked, with the application and the security framework logging at debug
 * level. A value of a record, a value in an external payload, an external identifier, the nonce and signature of a call
 * and the secrets are never logged, never written to the audit trail and never echoed by the list of deliveries or by a
 * refusal of the receiver. The trail does show that mappings changed and that records were written, by identifiers and
 * counts.
 */
@PlatformIntegrationTest
@Import(TestSecrets.class)
@TestPropertySource(properties = {
    "platform.outbox.enabled=true",
    "logging.file.name=" + MappingFlowsLogsAreCleanIT.LOG_FILE,
    "logging.level.app.platform=DEBUG",
    "logging.level.org.springframework.security=DEBUG",
    "logging.level.org.springframework.web=DEBUG"})
class MappingFlowsLogsAreCleanIT extends MappingSupport {

    static final String LOG_FILE = "target/it-logs/mapping-flows-clean.log";

    private FakeService service;

    @BeforeAll
    static void startWithAnEmptyLogFile() throws IOException {
        Files.deleteIfExists(Path.of(LOG_FILE));
    }

    @BeforeEach
    void startService() throws Exception {
        service = new FakeService();
    }

    @AfterEach
    void stopService() {
        service.close();
    }

    private static String canary(String what) {
        return what + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    @Test
    void noValueNoPayloadNoIdentifierNoSignatureAndNoSecretIsLoggedOrAuditedOrEchoed() throws Exception {
        String external = canary("ext");
        service.answer("POST /api/item", FakeService.Reply.json(201, "{\"id\":\"" + external + "\"}"));
        Linked linked = linked(service, "EXTERNAL_WINS");
        TestBrowser admin = linked.admin();
        String recordValue = canary("record-value");
        String payloadValue = canary("payload-value");
        String nonce = canary("nonce").replace("-", "");

        Response made = admin.postJson("/api/v1/objects/" + OBJECT + "/records",
                "{\"fields\":{\"" + TITLE_FIELD + "\":\"" + recordValue + "\"}}");
        assertThat(made.status()).as(made.body()).isEqualTo(201);
        Awaitility.await().atMost(Duration.ofSeconds(180)).pollInterval(Duration.ofMillis(100))
                .until(() -> service.asked("/api/item") >= 1);

        List<Response> answers = new ArrayList<>();
        String body = "{\"id\":\"" + external + "\",\"title\":\"" + payloadValue + "\"}";
        String[] headers = signed(SIGNING_SECRET, Long.toString(System.currentTimeMillis() / 1000), nonce, "item",
                body);
        answers.add(receive(linked.inboundId(), "item", body, headers));
        answers.add(receive(linked.inboundId(), "item", body, headers));
        answers.add(receive(linked.inboundId(), "item", body));
        answers.add(receive(linked.inboundId(), "item", "{not json " + payloadValue, signed(SIGNING_SECRET,
                Long.toString(System.currentTimeMillis() / 1000), canary("n").replace("-", ""), "item",
                "{not json " + payloadValue)));
        answers.add(receive("2".repeat(32), "item", body, headers));

        Response deliveries = admin.get(ROOT + "/" + linked.connection() + "/deliveries");
        answers.add(deliveries);
        Awaitility.await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(200)).until(
                () -> JsonPath.<List<String>>read(admin.get(ROOT + "/" + linked.connection() + "/deliveries").body(),
                        "$.data[*].state").stream().noneMatch("PENDING"::equals));
        String trail = admin.get("/api/v1/audit-events?limit=200").body();
        String log = Files.readString(Path.of(LOG_FILE));

        for (String text : List.of(recordValue, payloadValue, external, nonce, headers[5].substring(7), SIGNING_SECRET,
                OUT_TOKEN)) {
            assertThat(log).as("not logged: " + text).doesNotContain(text);
            assertThat(trail).as("not in the audit trail: " + text).doesNotContain(text);
            for (Response answer : answers) {
                assertThat(answer.body()).as("not echoed: " + text).doesNotContain(text);
            }
        }
        assertThat(answers.stream().filter(one -> one.status() >= 400).count()).as("the refusals happened")
                .isGreaterThanOrEqualTo(4);
        assertThat(trail).contains("integration.mapping.published").contains("data.record.updated");
    }
}
