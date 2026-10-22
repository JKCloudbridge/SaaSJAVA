package app.platform.inspection;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
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
 * The leak test of the template lifecycle (FA4, ADR-0012, ADR-0188): templates are made, sent for approval, numbered,
 * approved, published to departments, taken back, republished and retired, and the flows are refused in every way they
 * can be, with the application and the security framework logging at debug level. What the organization typed (the
 * name of a template, the cell text of its sheet, a part code, the name of a group, a department record's name) and
 * what the platform made from it (the number) is never logged, never written to the audit trail, never put in an event
 * and never echoed by a refusal. The audit trail does show that the template changed, by identifiers and counts.
 */
@PlatformIntegrationTest
@TestPropertySource(properties = {
    "platform.outbox.enabled=true",
    "platform.workflow.enabled=false",
    "logging.file.name=" + TemplateFlowsLogsAreCleanIT.LOG_FILE,
    "logging.level.app.platform=DEBUG",
    "logging.level.org.springframework.security=DEBUG",
    "logging.level.org.springframework.web=DEBUG"})
class TemplateFlowsLogsAreCleanIT extends TemplateSupport {

    static final String LOG_FILE = "target/it-logs/template-flows-clean.log";

    @BeforeAll
    static void startWithAnEmptyLogFile() throws IOException {
        Files.deleteIfExists(Path.of(LOG_FILE));
    }

    private static String typed(String what) {
        return what + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    @Test
    void nothingTypedOrMadeFromItIsLoggedWrittenToTheTrailOrEchoedAcrossTheLifecycle() throws IOException {
        Setting setting = setting();
        Person author = author(setting);
        Person approver = approver(setting);
        Person reader = reader(setting);
        String name = typed("template-name");
        String groupName = typed("group-name");
        String departmentName = typed("department-record");
        String cellText = typed("cell-text");
        String partCode = "P" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String clashName = typed("clash-name");
        String process = process(setting.admin(), "OneStep", step("Only", user(approver.userId())));
        String group = group(setting.admin(), groupName, reader);
        Response department = setting.admin().postJson(DEPARTMENTS, "{\"fields\":{\"name\":\"" + departmentName
                + "\",\"location\":\"PLANT1\",\"department\":\"PRD\",\"groupId\":\"" + group + "\"}}");
        assertThat(department.status()).as(department.body()).isEqualTo(201);

        // A counted template: made, written, sent, numbered, approved, published, taken back, republished.
        String counted = template(author.browser(), "{\"name\":\"" + name + "\",\"category\":\"WI\","
                + "\"subCategory\":\"PRD\"}");
        int one = firstVersion(author.browser(), counted);
        Response read = version(author.browser(), counted, one);
        Response filled = saveGrid(author.browser(), counted, one, rowVersion(read),
                withContent(gridOf(read), cellText));
        String request = submitted(author.browser(), counted, process);
        Response approved = approve(approver.browser(), request);
        String number = number(setting.admin(), counted);
        Response published = publish(approver.browser(), counted, null, "PLANT1/PRD");
        Response taken = unpublish(approver.browser(), counted);
        Response again = publish(approver.browser(), counted, 1, "PLANT1/PRD");
        Response retired = retire(approver.browser(), counted);

        // A typed number: made from the part code, then a second template that would clash with it.
        String typedOne = template(author.browser(), "{\"name\":\"" + typed("typed-name") + "\",\"category\":\"CP\","
                + "\"subCategory\":\"PRD\",\"partCode\":\"" + partCode + "\"}");
        fill(author.browser(), typedOne, firstVersion(author.browser(), typedOne));
        approve(approver.browser(), submitted(author.browser(), typedOne, process));
        String typedNumber = number(setting.admin(), typedOne);
        String typedTwo = template(author.browser(), "{\"name\":\"" + clashName + "\",\"category\":\"CP\","
                + "\"subCategory\":\"PRD\",\"partCode\":\"" + partCode + "\"}");
        fill(author.browser(), typedTwo, firstVersion(author.browser(), typedTwo));

        // Refusals of every kind.
        List<Response> refusals = new ArrayList<>();
        refusals.add(submit(author.browser(), typedTwo, process));
        refusals.add(publish(approver.browser(), typedOne, null, "PLANT1/QLT"));
        refusals.add(publish(approver.browser(), typedOne, null, "HEAD/HR"));
        refusals.add(publish(author.browser(), typedOne, null, "PLANT1/PRD"));
        refusals.add(retire(author.browser(), typedOne));
        refusals.add(unpublish(approver.browser(), typedOne));
        refusals.add(publish(reader.browser(), typedOne, null, "PLANT1/PRD"));
        refusals.add(setting.admin().request("PATCH", RECORDS + "/" + counted, "{\"fields\":{\"retired\":false},"
                + "\"version\":1}"));
        String trail = setting.admin().get("/api/v1/audit-events?limit=200").body();
        String log = Files.readString(Path.of(LOG_FILE));

        for (Response ok : List.of(filled, approved, published, taken, again, retired)) {
            assertThat(ok.status()).as(ok.body()).isEqualTo(200);
        }
        assertThat(number).isEqualTo("WI/PRD/01");
        assertThat(typedNumber).isEqualTo("CP/PRD/" + partCode);
        assertThat(log).as("the log has content to check").contains("platform.http");
        List<String> secrets = List.of(name, groupName, departmentName, cellText, partCode, clashName, number,
                typedNumber);
        for (String text : secrets) {
            assertThat(log).as("not logged: " + text).doesNotContain(text);
            if (!text.equals(groupName)) {
                // The audit record of making a group (not this sprint's) names the group the person made; ours must
                // not.
                assertThat(trail).as("not in the audit trail: " + text).doesNotContain(text);
            }
        }
        for (Response refused : refusals) {
            assertThat(refused.status()).as(refused.body()).isGreaterThanOrEqualTo(400);
            for (String text : secrets) {
                assertThat(refused.body()).as("a refusal does not echo: " + text).doesNotContain(text);
            }
        }
        for (Response answer : List.of(published, taken, again, retired)) {
            for (String text : List.of(groupName, departmentName, cellText, partCode)) {
                assertThat(answer.body()).as("the lifecycle view carries none of: " + text).doesNotContain(text);
            }
        }
        assertThat(JsonPath.<List<String>>read(setting.admin().get("/api/v1/audit-events?limit=200").body(),
                "$.data[*].type")).as("the trail shows what changed, by identifiers and counts")
                .contains("inspection.template.published", "inspection.template.unpublished",
                        "inspection.template.retired", "sheets.version.submitted", "sheets.version.reviewed",
                        "sheets.version.published", "data.record.shared", "data.record.unshared");
        assertThat(owner("select string_agg(attributes::text, ' ') from audit_record where (event_type like "
                + "'inspection.template.%' or event_type like 'data.record.%shared') and context_tenant_id = ?",
                setting.organization().id().value()).get(0)).contains("shares_made").contains("audiences")
                .doesNotContain(groupName);
        // The events the lifecycle put in the outbox carry identifiers and field names too, never a value.
        String events = String.join(" ", scalarsOf(setting));
        for (String text : secrets) {
            assertThat(events).as("not in an event: " + text).doesNotContain(text);
        }
    }

    private static List<String> scalarsOf(Setting setting) {
        return owner("select payload::text from outbox_event where tenant_id = ?", setting.organization().id().value());
    }
}
