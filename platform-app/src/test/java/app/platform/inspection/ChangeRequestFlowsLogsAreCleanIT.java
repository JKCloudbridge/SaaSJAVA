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
 * The leak test of the change requests (FA5, ADR-0012, ADR-0191): requests are made, commented on, accepted, declined,
 * turned into a version and refused in every way they can be, with the application and the security framework logging
 * at debug level. What the organization typed (the title of a request, a comment, the reason for declining, a label of
 * the grid the place was named after) is never logged, never written to the audit trail, never put in an event, never
 * kept in a notice and never echoed by a refusal. The audit trail does show that a request changed, by identifiers and
 * counts.
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

    @Test
    void nothingTypedIsLoggedWrittenToTheTrailKeptInAnEventOrANoticeOrEchoedByARefusal() throws IOException {
        World world = world();
        String label = typed("row-label");
        String title = typed("request-title");
        String firstComment = typed("first-comment");
        String reply = typed("owner-reply");
        String reason = typed("decline-reason");
        String otherTitle = typed("other-title");
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

        String request = asked(world.reader().browser(), template, title, 1, comment("r1", null, firstComment));
        Response replied = add(world.author().browser(), template, request, comment("r1", null, reply));
        String other = asked(world.colleague().browser(), template, otherTitle, 1);
        Response accepted = answerRequest(world.author().browser(), template, request, "ACCEPT", null);
        Response declined = answerRequest(world.approver().browser(), template, other, "DECLINE", reason);
        Response turned = turn(world.author().browser(), template, request);
        Response reopenRefused = answerRequest(world.author().browser(), template, request, "REOPEN", null);

        List<Response> refusals = new ArrayList<>();
        refusals.add(ask(world.reader().browser(), template, "<b>" + markup + "</b>", 1));
        refusals.add(ask(world.reader().browser(), template, "Fine", 1, comment("r1", null, "<i>" + markup + "</i>")));
        refusals.add(ask(world.reader().browser(), template, "Fine", 1, comment(markup, null, "Where")));
        refusals.add(ask(world.reader().browser(), template, "Fine", 99));
        refusals.add(add(world.reader().browser(), template, other, comment(null, null, markup)));
        refusals.add(answerRequest(world.reader().browser(), template, request, "ACCEPT", null));
        refusals.add(answerRequest(world.author().browser(), template, other, "DECLINE", "<u>" + markup + "</u>"));
        refusals.add(one(world.colleague().browser(), template, request));
        refusals.add(one(world.outsider().browser(), template, request));
        refusals.add(turn(world.reader().browser(), template, request));
        refusals.add(turn(world.author().browser(), template, request));

        String trail = world.setting().admin().get("/api/v1/audit-events?limit=200").body();
        String log = Files.readString(Path.of(LOG_FILE));
        // The department kept with a request (FA6A, ADR-0196) is the organization's words too: shown, never logged.
        String department = "PLANT1 - Plant 1 · PRD - Production";
        assertThat(one(world.reader().browser(), template, request).body()).as("the snapshot is shown")
                .contains(department);
        List<String> secrets = List.of(label, title, firstComment, reply, reason, otherTitle, markup, department);

        for (Response ok : List.of(replied, accepted, declined, turned)) {
            assertThat(ok.status()).as(ok.body()).isEqualTo(200);
        }
        assertThat(reopenRefused.status()).isEqualTo(409);
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
        assertThat(JsonPath.<List<String>>read(trail, "$.data[*].type")).as("the trail shows what changed")
                .contains("inspection.changerequest.created", "inspection.changerequest.commented",
                        "inspection.changerequest.accepted", "inspection.changerequest.declined",
                        "inspection.changerequest.turned");
        assertThat(owner("select string_agg(attributes::text, ' ') from audit_record where event_type like "
                + "'inspection.changerequest.%' and context_tenant_id = ?",
                world.setting().organization().id().value()).get(0)).contains("places").contains("comments");
        String events = String.join(" ", owner("select payload::text from outbox_event where tenant_id = ?",
                world.setting().organization().id().value()));
        String notices = String.join(" ", owner("select facts::text from notification where tenant_id = ?",
                world.setting().organization().id().value()));
        assertThat(notices).as("the notices carry facts, which exist").contains("requested");
        for (String text : secrets) {
            assertThat(events).as("not in an event: " + text).doesNotContain(text);
            assertThat(notices).as("not in a notice: " + text).doesNotContain(text);
        }
    }
}
