package app.platform.inspection;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.identity.PlatformRole;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestPlatform;
import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * The approval history of a few records (FA6E, ADR-0199): every request of each record, newest first, for the screen
 * that draws the story of a template and of its change requests. A record the caller cannot read is left out as if it
 * did not exist; the list is bounded; someone who is not signed in, another organization and the platform address get
 * nothing.
 */
@PlatformIntegrationTest
@TestPropertySource(properties = {"platform.outbox.enabled=true", "platform.workflow.enabled=false"})
class ApprovalHistoryIT extends ChangeRequestReviewSupport {

    private static String history(String object) {
        return "/api/v1/objects/" + object + "/records/approval-history";
    }

    private static String ids(String... ids) {
        List<String> quoted = new ArrayList<>();
        for (String id : ids) {
            quoted.add("\"" + id + "\"");
        }
        return "{\"recordIds\":[" + String.join(",", quoted) + "]}";
    }

    @Test
    void theRequestsOfATemplateComeBackNewestFirstForThosewhoCanReadIt() {
        World world = world();
        int draft = newVersion(world.author().browser(), world.template());
        assertThat(draft).isEqualTo(2);
        String second = editAndSend(world, draft);
        assertThat(approve(world.approver().browser(), second).status()).isEqualTo(200);

        Response answer = world.approver().browser().postJson(history(OBJECT), ids(world.template()));

        assertThat(answer.status()).as(answer.body()).isEqualTo(200);
        assertThat(JsonPath.<List<String>>read(answer.body(), "$.data[0].requests[*].summary.id")).hasSize(2)
                .first().isEqualTo(second);
        assertThat(JsonPath.<String>read(answer.body(), "$.data[0].recordId")).isEqualTo(world.template());
        assertThat(JsonPath.<List<String>>read(answer.body(), "$.data[0].requests[*].summary.status"))
                .containsOnly("APPROVED");
    }

    @Test
    void theApprovalOfAChangeRequestIsInTheHistoryOfItsReviewRecord() {
        Review review = review();
        String request = JsonPath.read(raised(review.world().reader().browser(), review.world().template(),
                "Walkway").body(), "$.data.id");
        String reviewId = reviewIdOf(one(review.world().reader().browser(), review.world().template(), request));

        Response answer = review.world().approver().browser().postJson(history(REQUEST_OBJECT), ids(reviewId));

        assertThat(answer.status()).as(answer.body()).isEqualTo(200);
        assertThat(JsonPath.<List<String>>read(answer.body(), "$.data[0].requests[*].summary.status"))
                .containsExactly("PENDING");
        assertThat(JsonPath.<List<String>>read(answer.body(), "$.data[0].requests[0].steps[*].label"))
                .containsExactly("Plant", "Corporate");
    }

    @Test
    void aRecordThePersonCannotReadAndAnotherOrganizationsRecordAreLeftOutAsIfTheyDidNotExist() {
        World mine = world();
        World theirs = world();

        Response outsider = mine.outsider().browser().postJson(history(OBJECT), ids(mine.template()));
        Response elsewhere = theirs.approver().browser().postJson(history(OBJECT), ids(mine.template()));
        Response invented = mine.approver().browser().postJson(history(OBJECT), ids(UUID.randomUUID().toString()));

        for (Response left : List.of(outsider, elsewhere, invented)) {
            assertThat(left.status()).as(left.body()).isEqualTo(200);
            assertThat(JsonPath.<List<Object>>read(left.body(), "$.data")).isEmpty();
        }
    }

    @Test
    void theListIsBoundedAndMustNotBeEmpty() {
        World world = world();
        String[] many = new String[21];
        for (int i = 0; i < many.length; i++) {
            many[i] = UUID.randomUUID().toString();
        }

        assertThat(world.approver().browser().postJson(history(OBJECT), ids(many)).status()).isEqualTo(400);
        assertThat(world.approver().browser().postJson(history(OBJECT), ids()).status()).isEqualTo(400);
        assertThat(world.approver().browser().postJson(history(OBJECT), "{\"recordIds\":[\"x\"]}").status())
                .isEqualTo(400);
    }

    @Test
    void nobodyNotSignedInNoPlatformAddressAndNoRequestWithoutTheForgeryHeaderGetsAnything() {
        World world = world();
        TestBrowser nobody = new TestBrowser(port, world.setting().organization().host());
        TestBrowser platform = TestPlatform.signedIn(port, TestPlatform.person(users, PlatformRole.PLATFORM_ADMIN));

        Response unauthenticated = nobody.postJson(history(OBJECT), ids(world.template()));
        Response onPlatform = platform.postJson(history(OBJECT), ids(world.template()));
        Response noHeader = world.approver().browser().postJsonWithoutCsrfHeader(history(OBJECT),
                ids(world.template()));

        assertThat(unauthenticated.status()).isEqualTo(401);
        assertThat(JsonPath.<String>read(unauthenticated.body(), "$.error.code")).isEqualTo("UNAUTHENTICATED");
        assertThat(onPlatform.status()).isEqualTo(404);
        assertThat(noHeader.status()).isEqualTo(403);
    }
}
