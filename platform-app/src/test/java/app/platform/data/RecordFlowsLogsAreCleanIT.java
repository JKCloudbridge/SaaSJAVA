package app.platform.data;

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
 * The leak test of the Sprint 14 flows (ADR-0089): records are created, read, listed, changed, refused, deleted,
 * restored and removed for good with the application and the security framework logging at debug level. What a person
 * typed into a record is never logged, never written to the audit trail, never put in an event, and a refusal never
 * echoes it. Names (the object, the identifier, the field names) may appear; values may not.
 */
@PlatformIntegrationTest
@TestPropertySource(properties = {
    "logging.file.name=" + RecordFlowsLogsAreCleanIT.LOG_FILE,
    "logging.level.app.platform=DEBUG",
    "logging.level.org.springframework.security=DEBUG",
    "logging.level.org.springframework.web=DEBUG",
    "logging.level.org.springframework.jdbc=DEBUG"})
class RecordFlowsLogsAreCleanIT extends RecordsSupport {

    static final String LOG_FILE = "target/it-logs/record-flows-clean.log";

    @BeforeAll
    static void startWithAnEmptyLogFile() throws IOException {
        Files.deleteIfExists(Path.of(LOG_FILE));
    }

    private static String typed(String what) {
        return what + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    @Test
    void nothingTypedIntoARecordIsLoggedOrStoredBesideItAcrossTheRecordFlows() throws IOException {
        TestBrowser admin = adminWithEmployee(organization());
        String name = typed("name-value");
        String department = typed("department-value");
        String notes = typed("notes-value");
        String changed = typed("changed-value");
        String tag = typed("tag-value");
        String refused = typed("refused-value");
        String stale = typed("stale-value");
        String nested = typed("nested-value");
        String hostileKey = typed("typed key");
        List<Response> refusals = new ArrayList<>();

        Response created = create(admin, "{\"name__c\":\"" + name + "\",\"department__c\":\"" + department
                + "\",\"notes__c\":\"" + notes + "\",\"salary__c\":123456.789,\"tags__c\":[\"" + tag + "\"]}");
        String id = idOf(created);
        admin.get(RECORDS + "/" + id);
        admin.get(RECORDS);
        patch(admin, id, 0, "{\"name__c\":\"" + changed + "\"}");
        refusals.add(patch(admin, id, 0, "{\"name__c\":\"" + stale + "\"}"));
        refusals.add(create(admin, "{\"name__c\":\"" + refused + "\",\"nope__c\":\"" + refused + "\"}"));
        refusals.add(create(admin, "{\"" + hostileKey + "\":\"" + refused + "\"}"));
        refusals.add(create(admin, "{\"name__c\":{\"deep\":\"" + nested + "\"}}"));
        refusals.add(create(admin, "{\"name__c\":\"" + refused + "\\u0000\"}"));
        refusals.add(create(admin, "{\"doubled__c\":\"" + refused + "\"}"));
        refusals.add(admin.request("PATCH", RECORDS + "/" + id, "{\"version\":1,\"clear\":[\"" + hostileKey
                + "\"],\"fields\":{\"name__c\":\"" + refused + "\"}}"));
        admin.request("DELETE", RECORDS + "/" + id, null);
        refusals.add(patch(admin, id, 2, "{\"name__c\":\"" + refused + "\"}"));
        admin.get(BIN);
        admin.postJson(BIN + "/" + id + "/restore", "{}");
        admin.request("DELETE", RECORDS + "/" + id, null);
        admin.request("DELETE", BIN + "/" + id, null);
        admin.request("DELETE", METADATA_OBJECTS + "/Employee__c/fields/name__c", null);
        String trail = admin.get("/api/v1/audit-events?limit=200").body();

        String log = Files.readString(Path.of(LOG_FILE));
        String stored = String.join(" ", owner("select payload::text from outbox_event "
                + "where payload ->> 'recordId' = ?", id))
                + String.join(" ", owner("select attributes::text from audit_record where record_id = ?", id));

        assertThat(log).as("the log has content to check").contains("platform.http");
        for (String text : List.of(name, department, notes, changed, tag, refused, stale, nested, hostileKey,
                "123456.789")) {
            assertThat(log).as("a value is not logged: " + text).doesNotContain(text);
            assertThat(stored).as("a value is not in an event or an audit record: " + text).doesNotContain(text);
            assertThat(trail).as("a value is not in the audit trail: " + text).doesNotContain(text);
        }
        for (Response response : refusals) {
            assertThat(response.status()).isIn(400, 409);
            for (String text : List.of(name, department, notes, changed, tag, refused, stale, nested, hostileKey)) {
                assertThat(response.body()).as("a refusal does not echo a value").doesNotContain(text);
            }
        }
        assertThat(stored).as("the trail and the events do carry names").contains("Employee__c").contains(id)
                .contains("name__c");
        assertThat(trail).contains("data.record.created").contains("data.record.updated")
                .contains("data.record.deleted").contains("data.record.restored").contains("data.record.purged");
    }
}
