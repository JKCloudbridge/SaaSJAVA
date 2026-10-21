package app.platform.solution;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations.Member;
import app.platform.testsupport.TestOrganizations.Organization;
import com.jayway.jsonpath.JsonPath;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * The leak test of the shipped-solution flows (Functional track FA1, ADR-0181): the application is listed, the policies
 * are made, assigned and refused, the feature is lost and regained, and records of the kept object are made, changed and
 * refused deletion, with the application and the security framework logging at debug level. What a person typed (the name
 * of a template, the words of a refused change of a shipped policy) is never logged, never written to the audit trail, and
 * never echoed by a refusal. The audit trail does show that the policies were made, by counts.
 */
@PlatformIntegrationTest
@TestPropertySource(properties = {
    "logging.file.name=" + SolutionFlowsLogsAreCleanIT.LOG_FILE,
    "logging.level.app.platform=DEBUG",
    "logging.level.org.springframework.security=DEBUG",
    "logging.level.org.springframework.web=DEBUG"})
class SolutionFlowsLogsAreCleanIT extends SolutionSupport {

    static final String LOG_FILE = "target/it-logs/solution-flows-clean.log";

    @BeforeAll
    static void startWithAnEmptyLogFile() throws IOException {
        Files.deleteIfExists(Path.of(LOG_FILE));
    }

    private static String typed(String what) {
        return what + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    @Test
    void nothingTypedIsLoggedOrWrittenToTheTrailAcrossTheSolutionFlows() throws IOException {
        Organization organization = organization();
        TestBrowser admin = adminOf(organization);
        String templateName = typed("template-name");
        String refusedWords = typed("refused-words");
        String refusedDescription = typed("refused-description");
        String refusedRecordText = typed("refused-record");

        switchOn(organization);
        Member reader = memberHolding(organization, admin, "Reader");
        TestBrowser readerBrowser = as(organization, reader);
        applicationsOf(readerBrowser);
        Response created = admin.postJson(RECORDS, "{\"fields\":{\"name\":\"" + templateName + "\"}}");
        String id = JsonPath.read(created.body(), "$.data.id");
        long version = ((Number) JsonPath.read(created.body(), "$.data.version")).longValue();
        admin.request("PATCH", RECORDS + "/" + id, "{\"version\":" + version + ",\"fields\":{\"retired\":true}}");
        Response deleteRefused = admin.request("DELETE", RECORDS + "/" + id, null);
        Response bulkRefused = admin.postJson(RECORDS + "/bulk", "{\"operation\":\"DELETE\",\"records\":[{\"id\":\""
                + id + "\"}]}");
        Response unknownField = admin.postJson(RECORDS, "{\"fields\":{\"" + refusedRecordText + "\":\""
                + refusedRecordText + "\"}}");
        Response readerWrite = readerBrowser.postJson(RECORDS, "{\"fields\":{\"name\":\"" + refusedRecordText + "\"}}");
        String reading = policyId(admin, "Reader");
        Response renameRefused = admin.request("PUT", POLICIES + "/" + reading, "{\"name\":\"" + refusedWords
                + "\",\"description\":\"" + refusedDescription + "\",\"abilities\":[]}");
        Response rightsRefused = admin.request("PUT", POLICIES + "/" + reading + "/data-access",
                "{\"objects\":[{\"key\":\"" + OBJECT + "\",\"actions\":[\"read\",\"delete\"]}],\"fields\":[]}");
        Response removeRefused = admin.request("DELETE", POLICIES + "/" + reading, null);
        switchOff(organization);
        Response hidden = admin.get(RECORDS + "/" + id);
        applicationsOf(admin);
        switchOn(organization);
        applicationsOf(admin);
        String trail = admin.get("/api/v1/audit-events?limit=200").body();

        String log = Files.readString(Path.of(LOG_FILE));

        assertThat(log).as("the log has content to check").contains("platform.http");
        for (String text : List.of(templateName, refusedWords, refusedDescription, refusedRecordText)) {
            assertThat(log).as("typed text is not logged: " + text).doesNotContain(text);
            assertThat(trail).as("typed text is not in the audit trail: " + text).doesNotContain(text);
        }
        for (Response refused : List.of(deleteRefused, bulkRefused, unknownField, readerWrite, renameRefused,
                rightsRefused, removeRefused, hidden)) {
            assertThat(refused.status()).as(refused.body()).isGreaterThanOrEqualTo(400);
            for (String text : List.of(templateName, refusedWords, refusedDescription, refusedRecordText)) {
                assertThat(refused.body()).as("a refusal does not echo what was typed").doesNotContain(text);
            }
        }
        assertThat(trail).as("the trail shows that the policies were made, by counts")
                .contains("access.policy.shipped_ensured");
        assertThat(log).as("no label of the solution is needed in a log line").doesNotContain("Templates: nothing");
    }
}
