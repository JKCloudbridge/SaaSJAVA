package app.platform.data.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.sharedkernel.ActorId;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations;
import app.platform.testsupport.TestUsers.TestUser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * Nothing typed into the record screens is logged, audited, announced or echoed by the Sprint 19 endpoints
 * (ADR-0012, ADR-0129, ADR-0130): the values of a validation, the name and the filter of a saved view, what is typed
 * into a picker, the names of people and records that a link is given.
 */
@PlatformIntegrationTest
@TestPropertySource(properties = {
    "logging.file.name=" + RecordScreensLogsAreCleanIT.LOG_FILE,
    "logging.level.app.platform=DEBUG",
    "logging.level.org.springframework.web=DEBUG",
    "logging.level.org.springframework.jdbc=DEBUG"})
class RecordScreensLogsAreCleanIT extends QuerySupport {

    static final String LOG_FILE = "target/it-logs/record-screens-clean.log";

    @BeforeAll
    static void startWithAnEmptyLogFile() throws IOException {
        Files.deleteIfExists(Path.of(LOG_FILE));
    }

    private static String typed(String what) {
        return what + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    @Test
    void noValueNameFilterOrSearchTextOfTheRecordScreensIsLoggedStoredOrAnnounced() throws IOException {
        Items items = items();
        TestBrowser admin = items.admin();
        admin.postJson(METADATA_OBJECTS + "/Target__c/fields",
                "{\"name\":\"name\",\"label\":\"name\",\"type\":\"TEXT\"}");
        String validated = typed("validated-value");
        String refused = typed("refused-value");
        String viewName = typed("view-name");
        String filter = typed("filter-value");
        String searchText = typed("picker-text");
        String targetName = typed("target-name");
        String personName = typed("person-name");
        String targetId = idOf(admin.postJson(TARGETS, "{\"fields\":{\"name__c\":\"" + targetName + "\"}}"));
        String email = "user-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12) + "@example.test";
        TestUser person = new TestUser(users.createActive(email, personName, "Pw-a-b-c-d-e-f-g-1".toCharArray(),
                ActorId.SYSTEM), email, "Pw-a-b-c-d-e-f-g-1");
        TestOrganizations.join(items.organization().tenant(), person, false);

        List<Response> answers = List.of(
                admin.postJson(ITEM_RECORDS + "/validate", "{\"fields\":{\"name__c\":\"" + validated + "\"}}"),
                admin.postJson(ITEM_RECORDS + "/validate", "{\"fields\":{\"qty__c\":\"" + refused + "\"}}"),
                admin.get(ITEMS + "/form"),
                admin.postJson(ITEMS + "/views", "{\"label\":\"" + viewName + "\",\"scope\":\"SHARED\",\"definition\":"
                        + "{\"filter\":" + on("name__c", "EQ", "\"" + filter + "\"") + "}}"),
                admin.postJson(ITEMS + "/views", "{\"label\":\"x\",\"scope\":\"PERSONAL\",\"definition\":{\"filter\":"
                        + on("qty__c", "EQ", "\"" + refused + "\"") + "}}"),
                admin.postJson(ITEMS + "/lookups/link__c/labels", "{\"ids\":[\"" + targetId + "\"]}"),
                admin.postJson(ITEMS + "/lookups/ownerId/labels", "{\"ids\":[\"" + person.user().id() + "\"]}"),
                admin.postJson(ITEMS + "/lookups/link__c/options", "{\"text\":\"" + searchText + "\"}"),
                admin.postJson(ITEMS + "/lookups/ownerId/options", "{\"text\":\"" + searchText + "\"}"));
        String trail = admin.get("/api/v1/audit-events?limit=200").body();

        String log = Files.readString(Path.of(LOG_FILE));
        for (String secret : List.of(validated, refused, viewName, filter, searchText, targetName, personName)) {
            assertThat(log).as("the log: " + secret).doesNotContain(secret);
            assertThat(trail).as("the audit trail: " + secret).doesNotContain(secret);
            assertThat(owner("select payload::text from outbox_event where payload::text like ?", "%" + secret + "%"))
                    .as("the events: " + secret).isEmpty();
            assertThat(owner("select attributes::text from audit_record where attributes::text like ?",
                    "%" + secret + "%")).as("the audit records: " + secret).isEmpty();
        }
        // What is typed is not echoed back in a refusal; what is asked for by name is answered (a label is the point).
        assertThat(answers.get(1).body()).doesNotContain(refused);
        assertThat(answers.get(4).body()).doesNotContain(refused);
        assertThat(answers.get(7).body()).doesNotContain(searchText);
    }
}
