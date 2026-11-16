package app.platform.inspection;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestHttp.Response;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * A Yes/No cell with a reason through the real application and database (SH5.1, ADR-0263), with the application and
 * the security framework logging at debug level: the author's prompt and the reason a person types are kept and shown
 * back to the people who may read them, but are never logged, never written to the audit trail, never put in an event
 * or a notice and never echoed by a refusal. Submit asks a required cell for its answer, never for the reason.
 */
@PlatformIntegrationTest
@TestPropertySource(properties = {
    "platform.outbox.enabled=true",
    "platform.workflow.enabled=false",
    "logging.file.name=" + YesNoReasonLogsAreCleanIT.LOG_FILE,
    "logging.level.app.platform=DEBUG",
    "logging.level.org.springframework.security=DEBUG",
    "logging.level.org.springframework.web=DEBUG"})
class YesNoReasonLogsAreCleanIT extends FillSupport {

    static final String LOG_FILE = "target/it-logs/yes-no-reason-clean.log";

    @BeforeAll
    static void startWithAnEmptyLogFile() throws IOException {
        Files.deleteIfExists(Path.of(LOG_FILE));
    }

    private static String typed(String what) {
        return what + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private static String sheet(String prompt) {
        return "{\"formatVersion\":2,\"sheets\":[{\"key\":\"main\",\"dimension\":2,"
                + "\"rowsMode\":\"FIXED\",\"columns\":[{\"id\":\"c1\",\"label\":\"Item\",\"type\":\"text\"},"
                + "{\"id\":\"c2\",\"label\":\"Amount\",\"type\":\"number\"}],\"headerRows\":[{\"id\":\"h1\"}],"
                + "\"rows\":[{\"id\":\"r1\",\"label\":\"First\"},{\"id\":\"r2\",\"label\":\"Second\"}],"
                + "\"cells\":{\"h1:c1\":{\"value\":\"Plant\"}},\"merges\":[],\"inputs\":{"
                + "\"r1:c1\":{\"type\":\"boolean_reason\",\"required\":true,\"prompt\":\"" + prompt + "\"}}}]}";
    }

    private static String three(String cells) {
        return "{\"formatVersion\":3,\"sheets\":[{\"key\":\"main\",\"rows\":[{\"id\":\"r1\",\"cells\":{" + cells
                + "}}]}]}";
    }

    @Test
    void thePromptAndTheReasonAreKeptForThePeopleWhoReadThemAndNeverLoggedOrEchoed() throws IOException {
        String prompt = typed("prompt-of-the-author");
        String reason = typed("reason-of-the-filler");
        String markup = typed("markup-in-a-reason");
        Setting setting = setting();
        Person author = author(setting);
        Person approver = approver(setting);
        Person filler = holding(setting, "Filler");
        String templateProcess = process(setting.admin(), "OneStep", step("Only", user(approver.userId())));
        String fillProcess = fillProcess(setting.admin(), "FillReview", step("Review", user(approver.userId())));
        department(setting.admin(), "PLANT1", "PRD", group(setting.admin(), "dept-" + System.nanoTime(), filler));
        String template = template(author.browser(), "WI", "PRD");
        int number = firstVersion(author.browser(), template);
        saveSheet(author.browser(), template, number, sheet(prompt));
        assertThat(approve(approver.browser(), submitted(author.browser(), template, templateProcess)).status())
                .isEqualTo(200);
        assertThat(publish(approver.browser(), template, null, "PLANT1/PRD").status()).isEqualTo(200);
        String fill = started(filler.browser(), template);

        long version = fillVersion(fillView(filler.browser(), fill));
        Response refused = saveCells(filler.browser(), fill, version,
                three("\"c1\":{\"answer\":true,\"reason\":\"<b>" + markup + "\"}"));
        Response reasonOnly = saveCells(filler.browser(), fill, version, three("\"c1\":{\"reason\":\"" + reason
                + "\"}"));
        Response unanswered = sendFill(filler.browser(), fill, fillProcess);
        Response answered = saveCells(filler.browser(), fill, fillVersion(fillView(filler.browser(), fill)),
                three("\"c1\":{\"answer\":false,\"reason\":\"" + reason + "\"}"));
        Response read = fillView(filler.browser(), fill);
        String request = sent(filler.browser(), fill, fillProcess);

        String trail = setting.admin().get("/api/v1/audit-events?limit=200").body();
        String log = Files.readString(Path.of(LOG_FILE));
        assertThat(refused.status()).as(refused.body()).isGreaterThanOrEqualTo(400);
        assertThat(reasonOnly.status()).as(reasonOnly.body()).isEqualTo(200);
        assertThat(unanswered.status()).as("a reason is not an answer").isEqualTo(409);
        assertThat(answered.status()).as(answered.body()).isEqualTo(200);
        assertThat(read.body()).as("the filler reads the reason back").contains(reason);
        assertThat(request).isNotBlank();
        assertThat(log).as("the log has content to check").contains("platform.http");
        String events = String.join(" ", owner("select payload::text from outbox_event where tenant_id = ?",
                setting.organization().id().value()));
        String notices = String.join(" ", owner("select facts::text from notification where tenant_id = ?",
                setting.organization().id().value()));
        for (String text : List.of(prompt, reason, markup)) {
            assertThat(log).as("not logged: " + text).doesNotContain(text);
            assertThat(trail).as("not in the audit trail: " + text).doesNotContain(text);
            assertThat(events).as("not in an event: " + text).doesNotContain(text);
            assertThat(notices).as("not in a notice: " + text).doesNotContain(text);
            assertThat(refused.body()).as("a refusal does not echo: " + text).doesNotContain(text);
            assertThat(unanswered.body()).as("a refusal does not echo: " + text).doesNotContain(text);
        }
    }
}
