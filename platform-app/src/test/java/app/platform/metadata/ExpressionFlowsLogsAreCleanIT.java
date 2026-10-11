package app.platform.metadata;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.expression.Expression;
import app.platform.expression.ExpressionEngine;
import app.platform.expression.ExpressionType;
import app.platform.expression.Limits;
import app.platform.expression.Purpose;
import app.platform.identity.Users;
import app.platform.licensing.Plans;
import app.platform.licensing.Subscriptions;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations;
import app.platform.testsupport.TestOrganizations.Organization;
import app.platform.testsupport.TestPlatform;
import app.platformapi.CheckExpressionRequest;
import app.platformapi.CreateValidationRuleRequest;
import app.platformapi.ExpressionTestView;
import app.platformapi.TestExpressionRequest;
import app.platformapi.TestValuesRequest;
import app.platformapi.UpdateValidationRuleRequest;
import app.platformapi.ValidationRuleView;
import com.jayway.jsonpath.JsonPath;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

/**
 * The leak test of the Sprint 13 flows (ADR-0081): formulas and rules are created, changed, checked, tried, refused,
 * published, undone and removed with the application and the security framework logging at debug level. The text of an
 * expression, the message of a rule and the sample values are text of the organization: they are never logged and never
 * written to the audit trail, and a refusal never echoes them. The audit viewer shows names and counts only.
 */
@PlatformIntegrationTest
@TestPropertySource(properties = {
    "logging.file.name=" + ExpressionFlowsLogsAreCleanIT.LOG_FILE,
    "logging.level.app.platform=DEBUG",
    "logging.level.org.springframework.security=DEBUG",
    "logging.level.org.springframework.web=DEBUG"})
class ExpressionFlowsLogsAreCleanIT {

    static final String LOG_FILE = "target/it-logs/expression-flows-clean.log";
    private static final String OBJECTS = "/api/v1/metadata/objects";
    private static final String EXPRESSIONS = "/api/v1/metadata/expressions";

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

    private static void ok(Response response) {
        assertThat(response.status()).as(response.body()).isBetween(200, 204);
    }

    @Test
    void nothingTypedIsLoggedOrWrittenToTheTrailAcrossTheExpressionFlows() throws IOException {
        Organization organization = TestOrganizations.create(users);
        TestPlatform.subscribe(plans, subscriptions, organization.id(), 5, 3);
        TestBrowser admin = TestOrganizations.signedIn(port, organization.host(), organization.admin().person());
        String ruleText = typed("rule-text");
        String ruleMessage = typed("rule-message");
        String ruleLabel = typed("rule-label");
        String formulaText = typed("formula-text");
        String checkedText = typed("checked-text");
        String triedText = typed("tried-text");
        String sampleValue = typed("sample-value");
        String refusedText = typed("refused-text");
        String setText = typed("set-text");
        String updatedText = typed("updated-text");
        List<String> shownToOthers = new ArrayList<>();
        List<Response> refusals = new ArrayList<>();

        ok(admin.postJson(OBJECTS, "{\"name\":\"Ledger\",\"label\":\"Ledger\",\"pluralLabel\":\"Ledgers\"}"));
        ok(admin.postJson(OBJECTS + "/Ledger__c/fields", "{\"name\":\"status\",\"label\":\"Status\","
                + "\"type\":\"TEXT\"}"));
        ok(admin.postJson(OBJECTS + "/Ledger__c/fields", "{\"name\":\"amount\",\"label\":\"Amount\","
                + "\"type\":\"NUMBER\"}"));
        // a rule and a formula, with the sentinel texts inside text literals, a label and a message
        ok(admin.postJson(OBJECTS + "/Ledger__c/validation-rules", "{\"name\":\"Closing\",\"label\":\"" + ruleLabel
                + "\",\"description\":\"" + ruleLabel + "\",\"expression\":\"status__c = '" + ruleText
                + "' AND amount__c > 0\",\"errorMessage\":\"" + ruleMessage + "\",\"errorField\":\"status__c\"}"));
        ok(admin.postJson(OBJECTS + "/Ledger__c/fields", "{\"name\":\"title\",\"label\":\"Title\",\"type\":\"FORMULA\","
                + "\"settings\":{\"expression\":\"CONCAT(status__c, '" + formulaText
                + "')\",\"resultType\":\"TEXT\"}}"));
        // checking and trying, with a mistake, a type error, sample values and a failure of the evaluation
        Response checkedBad = admin.postJson(EXPRESSIONS + "/check", "{\"objectApiName\":\"Ledger__c\","
                + "\"expression\":\"status__c = '" + checkedText + "' +\",\"purpose\":\"VALIDATION_RULE\"}");
        Response checkedType = admin.postJson(EXPRESSIONS + "/check", "{\"objectApiName\":\"Ledger__c\","
                + "\"expression\":\"'" + checkedText + "' + 1\",\"purpose\":\"FORMULA\"}");
        Response tried = admin.postJson(EXPRESSIONS + "/test", "{\"objectApiName\":\"Ledger__c\",\"expression\":"
                + "\"CONCAT('" + triedText + "', status__c)\",\"purpose\":\"FORMULA\",\"values\":{\"status__c\":\""
                + sampleValue + "\"}}");
        Response divided = admin.postJson(EXPRESSIONS + "/test", "{\"objectApiName\":\"Ledger__c\",\"expression\":"
                + "\"1 / amount__c\",\"purpose\":\"FORMULA\",\"values\":{\"amount__c\":\"0\"}}");
        Response storedTry = admin.postJson(OBJECTS + "/Ledger__c/validation-rules/Closing__c/test",
                "{\"values\":{\"status__c\":\"" + sampleValue + "\",\"amount__c\":\"1\"}}");
        // refusals: an unknown field, a wrong sample value
        refusals.add(admin.postJson(OBJECTS + "/Ledger__c/validation-rules", "{\"name\":\"Ghost\",\"label\":\"G\","
                + "\"expression\":\"ghost__c = '" + refusedText + "'\",\"errorMessage\":\"" + refusedText + "\"}"));
        refusals.add(admin.postJson(OBJECTS + "/Ledger__c/validation-rules", "{\"name\":\"Broken\",\"label\":\"B\","
                + "\"expression\":\"status__c = '" + refusedText + "' AND\",\"errorMessage\":\"M\"}"));
        refusals.add(admin.postJson(EXPRESSIONS + "/test", "{\"objectApiName\":\"Ledger__c\",\"expression\":\"1\","
                + "\"purpose\":\"FORMULA\",\"values\":{\"amount__c\":\"" + refusedText + "\"}}"));
        // a change set, an update, a removal and an undo
        String set = idOf(admin.postJson("/api/v1/metadata/change-sets", "{\"name\":\"Rules\","
                + "\"description\":\"\"}"));
        ok(admin.postJson("/api/v1/metadata/change-sets/" + set + "/changes", "{\"kind\":\"CREATE_VALIDATION_RULE\","
                + "\"objectApiName\":\"Ledger__c\",\"createValidationRule\":{\"name\":\"FromSet\",\"label\":\"F\","
                + "\"expression\":\"status__c = '" + setText + "'\",\"errorMessage\":\"" + setText + "\"}}"));
        ok(admin.postJson("/api/v1/metadata/change-sets/" + set + "/publish", "{}"));
        ok(admin.request("PUT", OBJECTS + "/Ledger__c/validation-rules/Closing__c", "{\"label\":\"" + ruleLabel
                + "\",\"description\":\"\",\"expression\":\"status__c = '" + updatedText + "'\",\"errorMessage\":\""
                + ruleMessage + "\",\"errorField\":null,\"active\":false,\"version\":0}"));
        ok(admin.postJson("/api/v1/metadata/releases/latest/rollback", "{}"));
        ok(admin.request("DELETE", OBJECTS + "/Ledger__c/validation-rules/FromSet__c", null));
        shownToOthers.add(admin.get("/api/v1/audit-events?limit=200").body());
        shownToOthers.add(admin.get("/api/v1/audit-events?kind=metadata&limit=200").body());

        String log = Files.readString(Path.of(LOG_FILE));

        assertThat(log).as("the log has content to check").contains("platform.http");
        for (String text : List.of(ruleText, ruleMessage, ruleLabel, formulaText, checkedText, triedText, sampleValue,
                refusedText, setText, updatedText)) {
            assertThat(log).as("typed text is not logged: " + text).doesNotContain(text);
            for (String body : shownToOthers) {
                assertThat(body).as("typed text is not in the audit trail: " + text).doesNotContain(text);
            }
        }
        assertThat(shownToOthers.get(1)).as("the trail does show the names and what changed")
                .contains("metadata.validationrule.created").contains("Ledger__c.Closing__c")
                .contains("metadata.validationrule.updated").contains("metadata.release.published");
        for (Response answer : List.of(checkedBad, checkedType)) {
            assertThat(answer.body()).as("an answer about a mistake does not echo what was typed")
                    .doesNotContain(checkedText);
        }
        for (Response refused : refusals) {
            assertThat(refused.status()).isGreaterThanOrEqualTo(400);
            assertThat(refused.body()).as("a refusal does not echo what was typed").doesNotContain(refusedText);
        }
        assertThat(divided.status()).isEqualTo(200);
        assertThat(tried.status()).isEqualTo(200);
        assertThat(storedTry.status()).isEqualTo(200);
    }

    private static String idOf(Response response) {
        return JsonPath.read(response.body(), "$.data.id");
    }

    @Test
    void theRequestAndViewRecordsAndTheEngineObjectsPrintNoTypedText() {
        String text = typed("printed-text");
        ExpressionEngine engine = new ExpressionEngine(Limits.standard());
        Expression expression = engine.parse("status__c = '" + text + "'");

        assertThat(new CreateValidationRuleRequest("N", text, text, text, text, text, true).toString())
                .doesNotContain(text);
        assertThat(new UpdateValidationRuleRequest(text, text, text, text, text, true, 0L).toString())
                .doesNotContain(text);
        assertThat(new CheckExpressionRequest("O__c", text, "FORMULA", null).toString()).doesNotContain(text);
        assertThat(new TestExpressionRequest("O__c", text, "FORMULA", Map.of("f__c", text)).toString())
                .doesNotContain(text);
        assertThat(new TestValuesRequest(Map.of("f__c", text)).toString()).doesNotContain(text);
        assertThat(new ExpressionTestView("VALUE", "TEXT", text, text, null).toString()).doesNotContain(text);
        assertThat(new ValidationRuleView("R__c", "O__c", text, text, text, text, "f__c", true, 0L).toString())
                .doesNotContain(text);
        assertThat(new ValidationRule("O__c", "R__c", text, text, text, text, null, true, 0).toString())
                .doesNotContain(text);
        assertThat(FormulaOutcome.of(text).toString()).doesNotContain(text);
        assertThat(expression.toString()).doesNotContain(text);
        assertThat(engine.compile("status__c = '" + text + "'", Map.of("status__c", ExpressionType.TEXT),
                Purpose.VALIDATION_RULE).toString()).doesNotContain(text);
    }
}
