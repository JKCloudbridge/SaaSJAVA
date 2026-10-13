package app.platform.data.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * Nothing typed into a query or a record is logged, audited, announced or echoed by the Sprint 16 flows (ADR-0089,
 * ADR-0100): filter values, cursors, linked values, the backfill and the removals with their links.
 */
@PlatformIntegrationTest
@TestPropertySource(properties = {
    "logging.file.name=" + QueryFlowsLogsAreCleanIT.LOG_FILE,
    "logging.level.app.platform=DEBUG",
    "logging.level.org.springframework.web=DEBUG",
    "logging.level.org.springframework.jdbc=DEBUG"})
class QueryFlowsLogsAreCleanIT extends QuerySupport {

    static final String LOG_FILE = "target/it-logs/query-flows-clean.log";

    @BeforeAll
    static void startWithAnEmptyLogFile() throws IOException {
        Files.deleteIfExists(Path.of(LOG_FILE));
    }

    private static String typed(String what) {
        return what + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    @Test
    void noFilterValueCursorLinkedValueOrRefusedValueIsLoggedOrStored() throws IOException {
        Items items = items();
        TestBrowser admin = items.admin();
        String name = typed("name-value");
        String filter = typed("filter-value");
        String refused = typed("refused-value");
        String linkedName = typed("linked-value");
        Response target = admin.postJson(TARGETS, "{\"fields\":{}}");
        String targetId = idOf(target);
        for (int i = 0; i < 4; i++) {
            put(admin, "{\"name__c\":\"" + name + i + "\",\"link__c\":\"" + targetId + "\",\"code__c\":\""
                    + linkedName + "\"}");
        }
        flagAll(admin, "name");
        settle(items.organization(), admin);
        List<Response> answers = new ArrayList<>();

        Response first = query(admin, "{\"filter\":" + on("name__c", "STARTS_WITH", "\"" + filter + "\"")
                + ",\"limit\":1}");
        answers.add(first);
        Response page = query(admin, "{\"sort\":" + by("name__c", false) + ",\"limit\":2,\"resolve\":[{\"field\":"
                + "\"link__c\",\"fields\":[\"id\"]}]}");
        answers.add(page);
        answers.add(query(admin, "{\"sort\":" + by("name__c", false) + ",\"limit\":2,\"cursor\":\"" + next(page)
                + "\"}"));
        answers.add(query(admin, ask(on("qty__c", "EQ", "\"" + refused + "\""), null, 10)));
        answers.add(query(admin, ask(on(refused, "EQ", "\"" + refused + "\""), null, 10)));
        answers.add(query(admin, "{\"cursor\":\"" + refused + "\"}"));
        admin.request("DELETE", TARGETS + "/" + targetId, null);
        String trail = admin.get("/api/v1/audit-events?limit=200").body();

        String log = Files.readString(Path.of(LOG_FILE));
        for (String secret : List.of(filter, refused, name + "0", linkedName)) {
            assertThat(log).as("the log: " + secret).doesNotContain(secret);
            assertThat(trail).as("the audit trail: " + secret).doesNotContain(secret);
            assertThat(owner("select payload::text from outbox_event where payload::text like ?", "%" + secret + "%"))
                    .as("the events: " + secret).isEmpty();
        }
        for (Response refusal : answers.subList(3, 6)) {
            assertThat(refusal.body()).doesNotContain(refused);
        }
        assertThat(log).doesNotContain(next(page));
    }
}
