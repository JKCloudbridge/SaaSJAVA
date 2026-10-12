package app.platform.metadata;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import com.jayway.jsonpath.JsonPath;
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
 * The leak test of the Sprint 11 flows (ADR-0066, ADR-0067): relationships, record types, change sets, checks,
 * publication, refusals, rollback and discarding run with the application and the security framework logging at debug
 * level. What a person typed (labels, descriptions, names of change sets, picklist values, a refused request's text) is
 * never logged and never written to the audit trail, and the answers to a refused request do not echo it.
 */
@PlatformIntegrationTest
@TestPropertySource(properties = {
    "logging.file.name=" + LifecycleFlowsLogsAreCleanIT.LOG_FILE,
    "logging.level.app.platform=DEBUG",
    "logging.level.org.springframework.security=DEBUG",
    "logging.level.org.springframework.web=DEBUG"})
class LifecycleFlowsLogsAreCleanIT extends LifecycleSupport {

    static final String LOG_FILE = "target/it-logs/lifecycle-flows-clean.log";

    @BeforeAll
    static void startWithAnEmptyLogFile() throws IOException {
        Files.deleteIfExists(Path.of(LOG_FILE));
    }

    private static String typed(String what) {
        return what + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    @Test
    void nothingTypedIsLoggedOrWrittenToTheTrailAcrossTheLifecycleFlows() throws IOException {
        TestBrowser admin = adminOf(organization());
        String setName = typed("set-name");
        String setDescription = typed("set-description");
        String objectLabel = typed("object-label");
        String fieldLabel = typed("field-label");
        String pickValue = typed("pick-value");
        String recordTypeLabel = typed("record-type-label");
        String recordTypeDescription = typed("record-type-description");
        String listLabel = typed("list-label");
        String refusedText = typed("refused-text");
        List<String> shownToOthers = new ArrayList<>();

        String set = createSetWith(admin, setName, setDescription);
        addChange(admin, set, change("CREATE_OBJECT", "Ledger__c", null, "createObject",
                "{\"name\":\"Ledger\",\"label\":\"" + objectLabel + "\",\"pluralLabel\":\"" + objectLabel + "s\"}"));
        addChange(admin, set, change("CREATE_FIELD", "Ledger__c", null, "createField",
                "{\"name\":\"stage\",\"label\":\"" + fieldLabel + "\",\"type\":\"PICKLIST\",\"settings\":"
                        + "{\"values\":[{\"value\":\"" + pickValue + "\",\"label\":\"" + pickValue
                        + "\",\"active\":true}]}}"));
        addChange(admin, set, change("CREATE_FIELD", "Ledger__c", null, "createField",
                "{\"name\":\"parent\",\"label\":\"Parent\",\"type\":\"LOOKUP\",\"settings\":{\"targetObject\":"
                        + "\"Ledger__c\",\"listLabel\":\"" + listLabel + "\"}}"));
        addChange(admin, set, change("CREATE_RECORD_TYPE", "Ledger__c", null, "createRecordType",
                "{\"name\":\"Internal\",\"label\":\"" + recordTypeLabel + "\",\"description\":\""
                        + recordTypeDescription + "\",\"picklistSubsets\":[{\"field\":\"stage__c\",\"values\":[\""
                        + pickValue + "\"]}]}"));
        validate(admin, set);
        admin.postJson(SETS + "/" + set + "/preview", "{}");
        publish(admin, set);
        admin.get(OBJECTS + "/Ledger__c/relationships");
        admin.get(OBJECTS + "/Ledger__c/record-types");
        admin.postJson(RELEASES + "/latest/rollback-check", "{}");
        admin.postJson(RELEASES + "/latest/rollback", "{}");

        String bad = createSet(admin, "Bad");
        addChange(admin, bad, change("CREATE_FIELD", "Ghost__c", null, "createField",
                "{\"name\":\"x\",\"label\":\"" + refusedText + "\",\"type\":\"TEXT\",\"defaultValue\":\""
                        + refusedText + "\"}"));
        Response refusedValidate = validate(admin, bad);
        Response refusedPublish = publish(admin, bad);
        Response refusedRecordType = createRecordType(admin, "Account", "{\"name\":\"Bad\",\"label\":\"" + refusedText
                + "\",\"picklistSubsets\":[{\"field\":\"nothing__c\",\"values\":[\"" + refusedText + "\"]}]}");
        admin.request("DELETE", SETS + "/" + bad, null);
        shownToOthers.add(admin.get("/api/v1/audit-events?limit=200").body());
        shownToOthers.add(admin.get("/api/v1/audit-events?kind=metadata&limit=200").body());

        String log = Files.readString(Path.of(LOG_FILE));

        assertThat(log).as("the log has content to check").contains("platform.http");
        for (String text : List.of(setName, setDescription, objectLabel, fieldLabel, pickValue, recordTypeLabel,
                recordTypeDescription, listLabel, refusedText)) {
            assertThat(log).as("typed text is not logged: " + text).doesNotContain(text);
            for (String body : shownToOthers) {
                assertThat(body).as("typed text is not in the audit trail: " + text).doesNotContain(text);
            }
        }
        for (Response refused : List.of(refusedValidate, refusedPublish, refusedRecordType)) {
            assertThat(refused.body()).as("a refusal does not echo what was typed").doesNotContain(refusedText);
        }
        assertThat(shownToOthers.get(1)).as("the trail does show names and what happened")
                .contains("metadata.changeset.created").contains("metadata.release.published")
                .contains("metadata.release.rolledback").contains("metadata.publish.refused");
    }

    private static String createSetWith(TestBrowser admin, String name, String description) {
        Response created = admin.postJson(SETS, "{\"name\":\"" + name + "\",\"description\":\"" + description + "\"}");
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        return JsonPath.read(created.body(), "$.data.id");
    }
}
