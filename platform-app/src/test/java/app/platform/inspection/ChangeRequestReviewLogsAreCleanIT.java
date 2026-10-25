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
 * The leak test of the review of change requests (FA6E, ADR-0012, ADR-0199): requests are raised, listed, searched,
 * sent through the review, accepted, declined and refused with the application logging at debug level. What the
 * organization typed (a title, a comment, a reason, the text of a search) is never logged, never written to the audit
 * trail, never put in an event, never kept in a notice and never echoed by a refusal. The notices and the audit trail
 * still show that things happened, by identifiers and counts.
 */
@PlatformIntegrationTest
@TestPropertySource(properties = {
    "platform.outbox.enabled=true",
    "platform.workflow.enabled=false",
    "logging.file.name=" + ChangeRequestReviewLogsAreCleanIT.LOG_FILE,
    "logging.level.app.platform=DEBUG",
    "logging.level.org.springframework.security=DEBUG",
    "logging.level.org.springframework.web=DEBUG"})
class ChangeRequestReviewLogsAreCleanIT extends ChangeRequestReviewSupport {

    static final String LOG_FILE = "target/it-logs/change-request-review-clean.log";

    @BeforeAll
    static void startWithAnEmptyLogFile() throws IOException {
        Files.deleteIfExists(Path.of(LOG_FILE));
    }

    private static String typed(String what) {
        return what + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    @Test
    void nothingTypedIsLoggedWrittenToTheTrailKeptInAnEventOrANoticeOrEchoedByARefusal() throws IOException {
        Review review = review();
        String template = review.world().template();
        String title = typed("request-title");
        String otherTitle = typed("other-title");
        String firstComment = typed("first-comment");
        String search = typed("search-text");
        String markup = typed("markup-attempt");

        Response first = raised(review.world().reader().browser(), template, title,
                comment("r1", null, firstComment));
        String firstId = JsonPath.read(first.body(), "$.data.id");
        String firstReview = reviewIdOf(first);
        Response listed = queryRequests(review.world().approver().browser(), "{\"search\":\"" + search + "\"}");
        Response matched = queryRequests(review.world().approver().browser(), "{\"search\":\"" + title + "\"}");
        Response rejected = reject(review.plant().browser(), approvalOf(review.plant().browser(), firstReview));
        Response second = raised(review.world().colleague().browser(), template, otherTitle);
        String secondReview = reviewIdOf(second);
        String approval = approvalOf(review.plant().browser(), secondReview);
        Response plantApproved = approve(review.plant().browser(), approval);
        Response corporateApproved = approveWith(review.corporate().browser(), approval,
                choices("location=PLANT1", "department=PLANT1/PRD"));
        Response copied = createCopy(review.plant().browser(), template, JsonPath.read(second.body(), "$.data.id"));
        Response published = approveWith(review.plant().browser(), approval, PUBLISH_CHOICE);

        List<Response> refusals = new ArrayList<>();
        refusals.add(ask(review.world().reader().browser(), template, "<b>" + markup + "</b>", 2));
        // The web framework logs a value it cannot parse at debug level, so these two send a fixed word.
        refusals.add(queryRequests(review.world().approver().browser(), "{\"sort\":\"sideways\"}"));
        refusals.add(queryRequests(review.world().approver().browser(), "{\"cursor\":\"" + markup + "\"}"));
        refusals.add(createCopy(review.world().reader().browser(), template, firstId));
        refusals.add(requestById(review.world().outsider().browser(), firstId));
        refusals.add(review.world().approver().browser().postJson(
                "/api/v1/objects/" + REQUEST_OBJECT + "/records/approval-history", "{\"recordIds\":[\"not-an-id\"]}"));

        String trail = review.world().setting().admin().get("/api/v1/audit-events?limit=200").body();
        String log = Files.readString(Path.of(LOG_FILE));
        List<String> secrets = List.of(title, otherTitle, firstComment, search, markup);

        assertThat(listed.status()).isEqualTo(200);
        assertThat(matched.status()).isEqualTo(200);
        for (Response ok : List.of(plantApproved, corporateApproved, rejected, copied, published)) {
            assertThat(ok.status()).as(ok.body()).isEqualTo(200);
        }
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
                .contains("inspection.changerequest.created", "inspection.changerequest.declined",
                        "inspection.changerequest.copied", "inspection.changerequest.published");
        String events = String.join(" ", owner("select payload::text from outbox_event where tenant_id = ?",
                review.world().setting().organization().id().value()));
        String notices = String.join(" ", owner("select facts::text from notification where tenant_id = ?",
                review.world().setting().organization().id().value()));
        assertThat(notices).as("the notices carry facts, which exist").contains("declined").contains("request");
        for (String text : secrets) {
            assertThat(events).as("not in an event: " + text).doesNotContain(text);
            assertThat(notices).as("not in a notice: " + text).doesNotContain(text);
        }
    }
}
