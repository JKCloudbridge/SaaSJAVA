package app.platform.metadata;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.identity.Users;
import app.platform.licensing.Plans;
import app.platform.licensing.Subscriptions;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations;
import app.platform.testsupport.TestOrganizations.Organization;
import app.platform.testsupport.TestPlatform;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

/**
 * The leak test of the Sprint 10 flows (ADR-0058): objects and fields are created, changed, refused and removed with
 * the application and the security framework logging at debug level. What a person typed (labels, descriptions, default
 * values, picklist values, a formula) is never logged and never written to the audit trail; the audit viewer shows the
 * names and counts only; a refused request does not echo what was typed.
 */
@PlatformIntegrationTest
@TestPropertySource(properties = {
    "logging.file.name=" + MetadataFlowsLogsAreCleanIT.LOG_FILE,
    "logging.level.app.platform=DEBUG",
    "logging.level.org.springframework.security=DEBUG",
    "logging.level.org.springframework.web=DEBUG"})
class MetadataFlowsLogsAreCleanIT {

    static final String LOG_FILE = "target/it-logs/metadata-flows-clean.log";
    private static final String OBJECTS = "/api/v1/metadata/objects";

    @LocalServerPort
    private int port;

    @Autowired
    private Users users;

    @Autowired
    private Plans plans;

    @Autowired
    private Subscriptions subscriptions;

    @BeforeAll
    static void startWithAnEmptyLogFile() throws IOException {
        Files.deleteIfExists(Path.of(LOG_FILE));
    }

    private static String typed(String what) {
        return what + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    @Test
    void nothingTypedIsLoggedOrWrittenToTheTrailAcrossTheMetadataFlows() throws IOException {
        Organization organization = TestOrganizations.create(users);
        TestPlatform.subscribe(plans, subscriptions, organization.id(), 5, 3);
        TestBrowser admin = TestOrganizations.signedIn(port, organization.host(), organization.admin().person());
        String objectLabel = typed("object-label");
        String objectDescription = typed("object-description");
        String fieldLabel = typed("field-label");
        String fieldDescription = typed("field-description");
        String defaultValue = typed("default-value");
        String pickValue = typed("pick-value");
        String pickLabel = typed("pick-label");
        String formula = typed("formula-text");
        String refusedLabel = typed("refused-label");
        List<String> shownToOthers = new ArrayList<>();

        admin.postJson(OBJECTS, "{\"name\":\"Ledger\",\"label\":\"" + objectLabel + "\",\"pluralLabel\":\""
                + objectLabel + "s\",\"description\":\"" + objectDescription + "\"}");
        admin.postJson(OBJECTS + "/Ledger__c/fields", "{\"name\":\"note\",\"label\":\"" + fieldLabel
                + "\",\"description\":\"" + fieldDescription + "\",\"type\":\"TEXT\",\"defaultValue\":\""
                + defaultValue + "\"}");
        admin.postJson(OBJECTS + "/Ledger__c/fields", "{\"name\":\"stage\",\"label\":\"Stage\",\"type\":\"PICKLIST\","
                + "\"settings\":{\"values\":[{\"value\":\"" + pickValue + "\",\"label\":\"" + pickLabel
                + "\",\"active\":true}]}}");
        admin.postJson(OBJECTS + "/Ledger__c/fields", "{\"name\":\"total\",\"label\":\"Total\",\"type\":\"FORMULA\","
                + "\"settings\":{\"expression\":\"" + formula + "\",\"resultType\":\"TEXT\"}}");
        admin.request("PUT", OBJECTS + "/Ledger__c/fields/note__c", "{\"label\":\"" + refusedLabel + "\","
                + "\"description\":\"" + fieldDescription + "\",\"required\":true,\"unique\":false,"
                + "\"defaultValue\":\"" + defaultValue + "\",\"settings\":{\"maxLength\":300},\"version\":0}");
        Response refusedCreate = admin.postJson(OBJECTS, "{\"name\":\"bad name " + refusedLabel + "\",\"label\":\""
                + refusedLabel + "\",\"pluralLabel\":\"x\"}");
        Response refusedField = admin.postJson(OBJECTS + "/Ledger__c/fields", "{\"name\":\"x\",\"label\":\""
                + refusedLabel + "\",\"type\":\"BOOLEAN\",\"defaultValue\":\"" + refusedLabel + "\"}");
        Response protectedChange = admin.request("PUT", OBJECTS + "/Account", "{\"label\":\"" + refusedLabel
                + "\",\"pluralLabel\":\"x\",\"description\":\"\",\"version\":0}");
        shownToOthers.add(admin.get("/api/v1/audit-events?limit=200").body());
        shownToOthers.add(admin.get("/api/v1/audit-events?kind=metadata&limit=200").body());
        admin.request("DELETE", OBJECTS + "/Ledger__c", null);

        String log = Files.readString(Path.of(LOG_FILE));

        assertThat(log).as("the log has content to check").contains("platform.http");
        for (String text : List.of(objectLabel, objectDescription, fieldLabel, fieldDescription, defaultValue,
                pickValue, pickLabel, formula, refusedLabel)) {
            assertThat(log).as("typed text is not logged: " + text).doesNotContain(text);
            for (String body : shownToOthers) {
                assertThat(body).as("typed text is not in the audit trail: " + text).doesNotContain(text);
            }
        }
        assertThat(shownToOthers.get(1)).as("the trail does show the names and what changed")
                .contains("metadata.object.created").contains("Ledger__c").contains("Ledger__c.note__c")
                .contains("metadata.change.refused");
        for (Response refused : List.of(refusedCreate, refusedField, protectedChange)) {
            assertThat(refused.body()).as("a refusal does not echo what was typed").doesNotContain(refusedLabel);
        }
        assertThat(new app.platformapi.CreateFieldRequest("n", objectLabel, objectDescription, "TEXT", false, false,
                defaultValue, null).toString()).doesNotContain(objectLabel).doesNotContain(defaultValue);
        assertThat(new PicklistValue(pickValue, pickLabel, true).toString()).doesNotContain(pickValue);
    }
}
