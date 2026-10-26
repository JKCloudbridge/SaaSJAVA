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
 * The leak test of the change requests (FA5, ADR-0012, ADR-0191; the register of FA8, ADR-0202): requests are made with
 * a register form, commented on, declined, given a revised copy, completed and published, and refused in every way they
 * can be, with the application and the security framework logging at debug level. What the organization typed (the
 * request number, what is asked, the basis, the horizontal deployment, the format that stops, a comment, the reason for
 * declining, the department, the approvers' names and the signature box, a label of the grid the place was named after)
 * is never logged, never written to the audit trail, never put in an event, never kept in a notice and never echoed by
 * a
 * refusal. The audit trail does show that a request changed, by identifiers and counts.
 */
@PlatformIntegrationTest
@TestPropertySource(properties = {
    "platform.outbox.enabled=true",
    "platform.workflow.enabled=false",
    "logging.file.name=" + ChangeRequestFlowsLogsAreCleanIT.LOG_FILE,
    "logging.level.app.platform=DEBUG",
    "logging.level.org.springframework.security=DEBUG",
    "logging.level.org.springframework.web=DEBUG"})
class ChangeRequestFlowsLogsAreCleanIT extends ChangeRequestSupport {

    static final String LOG_FILE = "target/it-logs/change-request-flows-clean.log";

    @BeforeAll
    static void startWithAnEmptyLogFile() throws IOException {
        Files.deleteIfExists(Path.of(LOG_FILE));
    }

    private static String typed(String what) {
        return what + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private static String quoted(String field, String text) {
        return "\"" + field + "\":\"" + text + "\"";
    }

    @Test
    void nothingTypedIsLoggedWrittenToTheTrailKeptInAnEventOrANoticeOrEchoedByARefusal() throws IOException {
        World world = world();
        String label = typed("row-label");
        String title = typed("request-title");
        String requestNo = typed("request-no");
        String basis = typed("basis-text");
        String horizontal = typed("horizontal-text");
        String obsoleted = typed("obsoleted-format");
        String firstComment = typed("first-comment");
        String reply = typed("owner-reply");
        String reason = typed("decline-reason");
        String otherTitle = typed("other-title");
        String approvedBy = typed("approved-by");
        String departmentText = typed("department-text");
        String signature = typed("signature-text");
        String markup = typed("markup-attempt");

        // A template whose row has a label of its own, published to the department.
        String template = template(world.author().browser(), "WI", "PRD");
        int one = firstVersion(world.author().browser(), template);
        Response read = version(world.author().browser(), template, one);
        assertThat(saveGrid(world.author().browser(), template, one, rowVersion(read),
                withContent(gridOf(read), label)).status()).isEqualTo(200);
        assertThat(approve(world.approver().browser(), submitted(world.author().browser(), template,
                world.process())).status()).isEqualTo(200);
        assertThat(publish(world.approver().browser(), template, null, "PLANT1/PRD").status()).isEqualTo(200);
        World review = new World(world.setting(), world.author(), world.approver(), world.reader(), world.colleague(),
                world.outsider(), template, world.process(), world.column());

        String form = "{" + quoted("requestNo", requestNo) + "," + quoted("changeRequested", title) + ","
                + quoted("basisForChange", basis) + "," + quoted("horizontalDeployment", horizontal) + ","
                + quoted("obsoletedFormat", obsoleted) + "}";
        Response made = askWith(world.reader().browser(), template, form, 1, comment("r1", null, firstComment));
        assertThat(made.status()).as(made.body()).isEqualTo(201);
        String request = JsonPath.read(made.body(), "$.data.id");
        Response replied = add(world.author().browser(), template, request, comment("r1", null, reply));
        Response inProgress = ask(world.colleague().browser(), template, otherTitle, 1);
        Response declined = decline(review, request, reason);
        String other = asked(world.colleague().browser(), template, otherTitle, 1);
        int copy = copied(world.approver().browser(), template, other);
        long registerVersion = registerVersion(world.approver().browser(), template, other);
        Response completed = completeRegister(world.approver().browser(), template, other, registerVersion,
                quoted("department", departmentText) + "," + quoted("approvedBy", approvedBy) + ","
                        + quoted("signatureNote", signature));
        Response file = world.reader().browser().get(changes(template) + "/" + request + "/register/file");
        Response published = approveWith(world.approver().browser(), approvalOfRequest(review, other), PUBLISH_CHOICE);

        List<Response> refusals = new ArrayList<>();
        refusals.add(ask(world.reader().browser(), template, "<b>" + markup + "</b>", 2));
        refusals.add(ask(world.reader().browser(), template, "Fine", 2, comment("r1", null, "<i>" + markup + "</i>")));
        refusals.add(ask(world.reader().browser(), template, "Fine", 2, comment(markup, null, "Where")));
        refusals.add(ask(world.reader().browser(), template, "Fine", 99));
        refusals.add(askWith(world.reader().browser(), template, "{" + quoted("requestNo", "<u>" + markup + "</u>")
                + ",\"changeRequested\":\"Fine\"}", 2));
        refusals.add(add(world.reader().browser(), template, other, comment(null, null, markup)));
        refusals.add(completeRegister(world.approver().browser(), template, other, registerVersion + 1,
                quoted("department", "<b>" + markup + "</b>")));
        refusals.add(createCopy(world.reader().browser(), template, other));
        refusals.add(one(world.colleague().browser(), template, request));
        refusals.add(one(world.outsider().browser(), template, request));

        String trail = world.setting().admin().get("/api/v1/audit-events?limit=200").body();
        String log = Files.readString(Path.of(LOG_FILE));
        // The department kept with a request (FA6A, ADR-0196) is the organization's words too: shown, never logged.
        String department = "PLANT1 - Plant 1 · PRD - Production";
        assertThat(one(world.reader().browser(), template, request).body()).as("the snapshot is shown")
                .contains(department);
        List<String> secrets = List.of(label, title, requestNo, basis, horizontal, obsoleted, firstComment, reply,
                reason, otherTitle, approvedBy, departmentText, signature, markup, department);

        for (Response ok : List.of(replied, declined, completed, file, published)) {
            assertThat(ok.status()).as(ok.body()).isEqualTo(200);
        }
        assertThat(inProgress.status()).isEqualTo(409);
        assertThat(copy).isEqualTo(2);
        assertThat(log).as("the log has content to check").contains("platform.http");
        for (String text : secrets) {
            assertThat(log).as("not logged: " + text).doesNotContain(text);
            assertThat(trail).as("not in the audit trail: " + text).doesNotContain(text);
        }
        for (Response refused : refusals) {
            assertThat(refused.status()).as(refused.body()).isGreaterThanOrEqualTo(400);
            for (String text : secrets) {
                assertThat(refused.body()).as("a refusal does not echo: " + text).doesNotContain(text);
            }
        }
        assertThat(inProgress.body()).doesNotContain(title);
        assertThat(JsonPath.<List<String>>read(trail, "$.data[*].type")).as("the trail shows what changed")
                .contains("inspection.changerequest.created", "inspection.changerequest.commented",
                        "inspection.changerequest.declined", "inspection.changerequest.copied",
                        "inspection.changerequest.register.completed", "inspection.changerequest.published");
        assertThat(owner("select string_agg(attributes::text, ' ') from audit_record where event_type like "
                + "'inspection.changerequest.%' and context_tenant_id = ?",
                world.setting().organization().id().value()).get(0)).contains("places").contains("comments");
        String events = String.join(" ", owner("select payload::text from outbox_event where tenant_id = ?",
                world.setting().organization().id().value()));
        String notices = String.join(" ", owner("select facts::text from notification where tenant_id = ?",
                world.setting().organization().id().value()));
        String registers = String.join(" ", owner("select to_jsonb(r)::text from inspection_change_register r "
                + "where tenant_id = ?", world.setting().organization().id().value()));
        assertThat(notices).as("the notices carry facts, which exist").contains("requested");
        assertThat(registers).as("the register keeps what was typed, so the check above has something to find")
                .contains(requestNo).contains(approvedBy);
        for (String text : secrets) {
            assertThat(events).as("not in an event: " + text).doesNotContain(text);
            assertThat(notices).as("not in a notice: " + text).doesNotContain(text);
        }
    }
}
