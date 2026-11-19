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
 * The leak test of the Home page that compares the plants (MC3, ADR-0012, ADR-0262): the page is asked for with every
 * kind of filter, with filters that are refused and after a limit and the words of the bands were changed, while the
 * application and the security framework log at debug level. A score, a limit, a plant, a month or a filter value is
 * never logged, never written to the audit trail, never put in an event or a notice and never echoed by a refusal. The
 * score used here is one no other number of the run can look like.
 */
@PlatformIntegrationTest
@TestPropertySource(properties = {
    "platform.outbox.enabled=true",
    "platform.workflow.enabled=false",
    "logging.file.name=" + ManagementHomeLogsAreCleanIT.LOG_FILE,
    "logging.level.app.platform=DEBUG",
    "logging.level.org.springframework.security=DEBUG",
    "logging.level.org.springframework.web=DEBUG"})
class ManagementHomeLogsAreCleanIT extends ManagementHomeSupport {

    static final String LOG_FILE = "target/it-logs/management-home-clean.log";
    private static final String DISTINCT_SCORE = "87.37";

    @BeforeAll
    static void startWithAnEmptyLogFile() throws IOException {
        Files.deleteIfExists(Path.of(LOG_FILE));
    }

    private static String typed(String what) {
        return what + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    @Test
    void noScoreLimitPlantMonthOrFilterValueIsLoggedAuditedPublishedOrEchoed() throws IOException {
        Plants plants = scored();
        TestBrowser approver = plants.world().approver().browser();
        var admin = plants.world().setting().admin();
        String[] month = month(plants, plants.head1(), "PLANT1", CURRENT, DISTINCT_SCORE, "100");
        assertThat(approve(plants.reviewerA().browser(), month[1]).status()).isEqualTo(200);
        assertThat(approve(approver, month[1]).status()).isEqualTo(200);
        String junkPlant = typed("nowhere");
        String junkMonth = typed("not-a-month");
        String junkForm = UUID.randomUUID().toString();

        List<Response> answers = new ArrayList<>();
        answers.add(approver.postJson(HOME, "{}"));
        answers.add(approver.postJson(HOME, "{\"month\":\"last\",\"span\":3}"));
        answers.add(approver.postJson(HOME, "{\"month\":\"" + CURRENT + "\",\"span\":12,\"plant\":\"PLANT1\"}"));
        answers.add(plants.head1().browser().postJson(HOME, "{}"));
        answers.add(plants.head2().browser().postJson(HOME, "{}"));
        assertThat(admin.request("PUT", "/api/v1/application-settings",
                "{\"application\":\"management-core\",\"key\":\"scoreLimit\",\"value\":93}").status()).isEqualTo(200);
        assertThat(admin.request("PUT", "/api/v1/application-settings",
                "{\"application\":\"management-core\",\"key\":\"excellentAbove\",\"value\":97}").status())
                .isEqualTo(200);
        answers.add(approver.postJson(HOME, "{}"));
        List<Response> refusals = new ArrayList<>();
        refusals.add(approver.postJson(HOME, "{\"plant\":\"" + junkPlant + "\"}"));
        refusals.add(approver.postJson(HOME, "{\"month\":\"" + junkMonth + "\"}"));
        refusals.add(approver.postJson(HOME, "{\"form\":\"" + junkForm + "\"}"));
        refusals.add(admin.request("PUT", "/api/v1/application-settings",
                "{\"application\":\"management-core\",\"key\":\"goodAbove\",\"value\":98}"));

        String log = Files.readString(Path.of(LOG_FILE));
        var organization = plants.world().setting().organization().id().value();
        String trail = String.join(" ", owner("select string_agg(attributes::text, ' ') from audit_record where "
                + "context_tenant_id = ?", organization));
        String events = String.join(" ", owner("select payload::text from outbox_event where tenant_id = ?",
                organization));
        String notices = String.join(" ", owner("select facts::text from notification where tenant_id = ?",
                organization));

        answers.forEach(answer -> assertThat(answer.status()).as(answer.body()).isEqualTo(200));
        assertThat(answers.get(0).body()).as("the page does carry the score to the person who may see it")
                .contains(DISTINCT_SCORE);
        assertThat(log).as("the log has content to check").contains("platform.http");
        for (Response refused : refusals) {
            assertThat(refused.status()).as(refused.body()).isEqualTo(400);
            for (String text : List.of(junkPlant, junkMonth, junkForm, "98")) {
                // The answer carries a random request id and trace id: a short text such as "98" can sit inside one by chance.
                String withoutIds = refused.body().replaceAll("\"(requestId|traceId)\":\"[^\"]*\"", "");
                assertThat(withoutIds).as("a refusal does not echo: " + text).doesNotContain(text);
            }
        }
        for (String text : List.of(DISTINCT_SCORE, junkPlant, junkMonth, junkForm)) {
            assertThat(log).as("not logged: " + text).doesNotContain(text);
            assertThat(trail).as("not in the audit trail: " + text).doesNotContain(text);
            assertThat(events).as("not in an event: " + text).doesNotContain(text);
            assertThat(notices).as("not in a notice: " + text).doesNotContain(text);
        }
        assertThat(trail).as("a setting is audited by its key and the kind of change, never its value")
                .contains("scoreLimit").doesNotContain("\"93\"").doesNotContain("\"97\"");
    }
}
