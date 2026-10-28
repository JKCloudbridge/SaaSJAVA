package app.platform.inspection;

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
 * The leak test of the home page (FA9, ADR-0012, ADR-0203): the page is asked for with filters, with filters that are
 * refused and with every period, while the application and the security framework log at debug level. A filter value,
 * a name a person gave a template and the department codes of the organization are never logged, never written to the
 * audit trail, never put in an event or a notice and never echoed by a refusal. The two small records the page reads
 * (the departments of a filler, what happened to a template) leave identifiers and kinds in the audit trail and nothing
 * else.
 */
@PlatformIntegrationTest
@TestPropertySource(properties = {
    "platform.outbox.enabled=true",
    "platform.workflow.enabled=false",
    "logging.file.name=" + QualityHomeLogsAreCleanIT.LOG_FILE,
    "logging.level.app.platform=DEBUG",
    "logging.level.org.springframework.security=DEBUG",
    "logging.level.org.springframework.web=DEBUG"})
class QualityHomeLogsAreCleanIT extends FillSupport {

    static final String LOG_FILE = "target/it-logs/quality-home-clean.log";
    private static final String HOME = "/api/v1/quality-home/summary";

    @BeforeAll
    static void startWithAnEmptyLogFile() throws IOException {
        Files.deleteIfExists(Path.of(LOG_FILE));
    }

    private static String typed(String what) {
        return what + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    @Test
    void nothingTypedOrChosenIsLoggedAuditedPublishedOrEchoed() throws IOException {
        World world = world(2, "FIXED_AND_REPEATABLE", false);
        TestBrowser approver = world.approver().browser();
        String junkLocation = typed("nowhere");
        String junkDepartment = typed("no-department");
        String junkPeriod = typed("not-a-period");

        String fill = started(world.filler().browser(), world.template());
        saved(world.filler().browser(), fill, complete());
        assertThat(world.filler().browser().postJson(FILL_INS + "/" + fill + "/submit", "{}").status()).isEqualTo(200);

        List<Response> answers = new ArrayList<>();
        answers.add(approver.postJson(HOME, "{}"));
        answers.add(approver.postJson(HOME, "{\"period\":\"7d\",\"location\":\"PLANT1\",\"department\":\"PRD\"}"));
        answers.add(approver.postJson(HOME, "{\"period\":\"12m\"}"));
        List<Response> refusals = new ArrayList<>();
        refusals.add(approver.postJson(HOME, "{\"location\":\"" + junkLocation + "\"}"));
        refusals.add(approver.postJson(HOME, "{\"department\":\"" + junkDepartment + "\"}"));
        refusals.add(approver.postJson(HOME, "{\"period\":\"" + junkPeriod + "\"}"));
        String log = Files.readString(Path.of(LOG_FILE));
        String trail = String.join(" ", owner("select string_agg(attributes::text, ' ') from audit_record where "
                + "context_tenant_id = ?", world.setting().organization().id().value()));
        String events = String.join(" ", owner("select payload::text from outbox_event where tenant_id = ?",
                world.setting().organization().id().value()));
        String notices = String.join(" ", owner("select facts::text from notification where tenant_id = ?",
                world.setting().organization().id().value()));

        answers.forEach(answer -> assertThat(answer.status()).as(answer.body()).isEqualTo(200));
        assertThat(log).as("the log has content to check").contains("platform.http");
        for (Response refused : refusals) {
            assertThat(refused.status()).as(refused.body()).isEqualTo(400);
            for (String text : List.of(junkLocation, junkDepartment, junkPeriod)) {
                assertThat(refused.body()).as("a refusal does not echo: " + text).doesNotContain(text);
            }
        }
        for (String text : List.of(junkLocation, junkDepartment, junkPeriod)) {
            assertThat(log).as("not logged: " + text).doesNotContain(text);
            assertThat(trail).as("not in the audit trail: " + text).doesNotContain(text);
            assertThat(events).as("not in an event: " + text).doesNotContain(text);
            assertThat(notices).as("not in a notice: " + text).doesNotContain(text);
        }
        assertThat(log).as("a filter that was accepted is not logged either").doesNotContain("PLANT1/PRD");
        assertThat(trail).as("the events of a template carry no department").doesNotContain("PLANT1");
    }
}
