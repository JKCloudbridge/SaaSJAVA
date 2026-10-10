package app.platform.metadata;

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
 * The leak test of the Sprint 12 flows (ADR-0069 to ADR-0073): layouts, record pages, applications and assignments are
 * made, changed, refused, published through a change set, rolled back and removed with the application and the
 * security framework logging at debug level. What a person typed (labels, descriptions, section and tab labels, link
 * labels, a refused definition's text) is never logged and never written to the audit trail, and the answers to a
 * refused request do not echo it.
 */
@PlatformIntegrationTest
@TestPropertySource(properties = {
    "logging.file.name=" + DefinitionFlowsLogsAreCleanIT.LOG_FILE,
    "logging.level.app.platform=DEBUG",
    "logging.level.org.springframework.security=DEBUG",
    "logging.level.org.springframework.web=DEBUG"})
class DefinitionFlowsLogsAreCleanIT extends DefinitionsSupport {

    static final String LOG_FILE = "target/it-logs/definition-flows-clean.log";

    @BeforeAll
    static void startWithAnEmptyLogFile() throws IOException {
        Files.deleteIfExists(Path.of(LOG_FILE));
    }

    private static String typed(String what) {
        return what + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    @Test
    void nothingTypedIsLoggedOrWrittenToTheTrailAcrossTheDefinitionFlows() throws IOException {
        TestBrowser admin = adminWithDealAndContact();
        String layoutLabel = typed("layout-label");
        String layoutDescription = typed("layout-description");
        String sectionLabel = typed("section-label");
        String pageLabel = typed("page-label");
        String tabLabel = typed("tab-label");
        String actionLabel = typed("action-label");
        String ruleValue = typed("rule-value");
        String appLabel = typed("app-label");
        String linkLabel = typed("link-label");
        String refusedText = typed("refused-text");
        List<String> shownToOthers = new ArrayList<>();

        String layout = "{\"sections\":[{\"label\":\"" + sectionLabel + "\",\"fields\":[{\"field\":\"code__c\"},"
                + "{\"field\":\"stage__c\"}]}]}";
        Response made = admin.postJson(OBJECTS + "/Deal__c/layouts", "{\"name\":\"Details\",\"label\":\""
                + layoutLabel + "\",\"description\":\"" + layoutDescription + "\",\"definition\":" + layout + "}");
        admin.request("PUT", OBJECTS + "/Deal__c/layouts/Details__c", "{\"label\":\"" + layoutLabel
                + "\",\"description\":\"changed\",\"definition\":" + layout + ",\"version\":" + versionOf(made) + "}");
        admin.postJson(OBJECTS + "/Deal__c/record-pages", "{\"name\":\"DealPage\",\"label\":\"" + pageLabel
                + "\",\"description\":\"\",\"definition\":{\"layout\":\"Details__c\",\"components\":["
                + "{\"key\":\"details\",\"type\":\"DETAILS\",\"visibleWhen\":{\"field\":\"stage__c\","
                + "\"operator\":\"EQUALS\",\"value\":\"" + ruleValue + "\"}},"
                + "{\"key\":\"more\",\"type\":\"TABS\",\"tabs\":[{\"label\":\"" + tabLabel + "\",\"components\":["
                + "{\"key\":\"log\",\"type\":\"HISTORY\"}]}]}],\"actions\":[{\"action\":\"EDIT\",\"label\":\""
                + actionLabel + "\"}]}}");
        admin.postJson(APPLICATIONS, "{\"name\":\"Sales\",\"label\":\"" + appLabel + "\",\"description\":\"\","
                + "\"definition\":{\"items\":[{\"type\":\"LINK\",\"label\":\"" + linkLabel
                + "\",\"path\":\"/members\"}]}}");
        admin.postJson(OBJECTS + "/Deal__c/layout-assignments", "{\"layout\":\"Details__c\"}");
        admin.get(MY_APPLICATIONS);
        admin.get(OBJECTS + "/Deal__c/layout-resolution");

        String set = createSet(admin, "Draft");
        addChange(admin, set, change("CREATE_LAYOUT", "Deal__c", null, "createLayout", layoutRequest("Other", layout)));
        validate(admin, set);
        publish(admin, set);
        admin.postJson(RELEASES + "/latest/rollback", "{}");

        Response refusedLayout = admin.postJson(OBJECTS + "/Deal__c/layouts", "{\"name\":\"Bad\",\"label\":\""
                + refusedText + "\",\"description\":\"" + refusedText + "\",\"definition\":{\"sections\":[{\"label\":\""
                + refusedText + "\",\"colour\":\"" + refusedText + "\",\"fields\":[{\"field\":\"" + refusedText
                + " + 1\"}]}]}}");
        Response refusedApplication = admin.postJson(APPLICATIONS, "{\"name\":\"Bad\",\"label\":\"" + refusedText
                + "\",\"description\":\"\",\"definition\":{\"items\":[{\"type\":\"LINK\",\"label\":\"" + refusedText
                + "\",\"path\":\"https://" + refusedText + ".example.test\"}]}}");
        Response refusedPage = admin.postJson(OBJECTS + "/Deal__c/record-pages", "{\"name\":\"Bad\",\"label\":\""
                + refusedText + "\",\"description\":\"\",\"definition\":{\"layout\":\"Details__c\",\"components\":["
                + "{\"key\":\"d\",\"type\":\"DETAILS\",\"visibleWhen\":{\"expression\":\"" + refusedText + "\"}}]}}");
        admin.request("DELETE", OBJECTS + "/Deal__c/layout-assignments/default", null);
        admin.request("DELETE", APPLICATIONS + "/Sales__c", null);
        shownToOthers.add(admin.get("/api/v1/audit-events?limit=200").body());
        shownToOthers.add(admin.get("/api/v1/audit-events?kind=metadata&limit=200").body());

        String log = Files.readString(Path.of(LOG_FILE));

        assertThat(log).as("the log has content to check").contains("platform.http");
        for (String text : List.of(layoutLabel, layoutDescription, sectionLabel, pageLabel, tabLabel, actionLabel,
                ruleValue, appLabel, linkLabel, refusedText)) {
            assertThat(log).as("typed text is not logged: " + text).doesNotContain(text);
            for (String body : shownToOthers) {
                assertThat(body).as("typed text is not in the audit trail: " + text).doesNotContain(text);
            }
        }
        for (Response refused : List.of(refusedLayout, refusedApplication, refusedPage)) {
            assertThat(refused.status()).isEqualTo(400);
            assertThat(refused.body()).as("a refusal does not echo what was typed").doesNotContain(refusedText);
        }
        assertThat(shownToOthers.get(1)).as("the trail does show names and what happened")
                .contains("metadata.layout.created").contains("metadata.recordpage.created")
                .contains("metadata.application.created").contains("metadata.layoutassignment.created")
                .contains("metadata.layoutassignment.deleted").contains("metadata.release.rolledback");
    }
}
