package app.platform.inspection;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.identity.PlatformRole;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestPlatform;
import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * The list of change requests across templates, and one request by its own identifier (FA6E, ADR-0199): what each
 * kind of person sees, the search, the order, the paging, another organization's requests never, a forged
 * organization, a platform administrator, and someone who is not signed in. Who sees which request is the rule of the
 * template page (own requests, and every request of a template the person may change), asked again for each template
 * of a batch.
 */
@PlatformIntegrationTest
@TestPropertySource(properties = {"platform.outbox.enabled=true", "platform.workflow.enabled=false"})
class ChangeRequestListIT extends ChangeRequestReviewSupport {

    private static List<String> ids(Response page) {
        assertThat(page.status()).as(page.body()).isEqualTo(200);
        return JsonPath.read(page.body(), "$.data.rows[*].id");
    }

    @Test
    void eachPersonSeesTheirOwnRequestsAndThePeopleWhoMayChangeTheTemplateSeeEveryRequest() {
        World world = world();
        String alpha = asked(world.reader().browser(), world.template(), "Alpha wording", 1);
        String beta = asked(world.colleague().browser(), world.template(), "Beta wording", 1);
        String gamma = asked(world.reader().browser(), world.template(), "Gamma wording", 1);

        assertThat(ids(queryRequests(world.reader().browser(), "{}"))).containsExactlyInAnyOrder(alpha, gamma);
        assertThat(ids(queryRequests(world.colleague().browser(), "{}"))).containsExactly(beta);
        assertThat(ids(queryRequests(world.author().browser(), "{}"))).as("the owner of the template")
                .containsExactlyInAnyOrder(alpha, beta, gamma);
        assertThat(ids(queryRequests(world.approver().browser(), "{}")))
                .as("the approver, who may change every template").containsExactlyInAnyOrder(alpha, beta, gamma);
        assertThat(ids(queryRequests(world.outsider().browser(), "{}"))).as("a member with no policy of the solution")
                .isEmpty();
    }

    @Test
    void aRowSaysWhatTheRequestIsAboutWhoAskedFromWhichDepartmentAndWhere() {
        World world = world();
        String request = asked(world.reader().browser(), world.template(), "Alpha wording", 1);

        Response page = queryRequests(world.approver().browser(), "{}");

        String row = "$.data.rows[?(@.id=='" + request + "')]";
        assertThat(JsonPath.<List<String>>read(page.body(), row + ".title")).containsExactly("Alpha wording");
        assertThat(JsonPath.<List<String>>read(page.body(), row + ".status")).containsExactly("OPEN");
        assertThat(JsonPath.<List<Integer>>read(page.body(), row + ".versionNo")).containsExactly(1);
        assertThat(JsonPath.<List<String>>read(page.body(), row + ".templateId")).containsExactly(world.template());
        assertThat(JsonPath.<List<String>>read(page.body(), row + ".requesterDepartment")).hasSize(1);
        assertThat(JsonPath.<List<Boolean>>read(page.body(), row + ".mine")).containsExactly(false);
        assertThat(JsonPath.<List<Boolean>>read(queryRequests(world.reader().browser(), "{}").body(), row + ".mine"))
                .containsExactly(true);
    }

    @Test
    void theSearchMatchesTheTitleAndTheNameOfThePersonWhoAskedAndNothingElse() {
        World world = world();
        String alpha = asked(world.reader().browser(), world.template(), "Alpha wording", 1);
        String beta = asked(world.colleague().browser(), world.template(), "Beta wording", 1);
        String readerName = JsonPath.read(one(world.approver().browser(), world.template(), alpha).body(),
                "$.data.requesterName");

        assertThat(ids(queryRequests(world.approver().browser(), "{\"search\":\"alpha\"}"))).containsExactly(alpha);
        assertThat(ids(queryRequests(world.approver().browser(), "{\"search\":\"nothing like it\"}"))).isEmpty();
        assertThat(ids(queryRequests(world.approver().browser(), "{\"search\":\"" + readerName + "\"}")))
                .as("the name of the person who asked").contains(alpha);
        assertThat(ids(queryRequests(world.approver().browser(), "{\"search\":\"%\"}")))
                .as("a wildcard is text, not a pattern").isEmpty();
        assertThat(ids(queryRequests(world.reader().browser(), "{\"search\":\"beta\"}")))
                .as("a search never reaches what the person may not see").doesNotContain(beta);
    }

    @Test
    void theOrderAndTheStatusNarrowingAreTheServers() {
        World world = world();
        String first = asked(world.reader().browser(), world.template(), "First", 1);
        String second = asked(world.colleague().browser(), world.template(), "Second", 1);
        assertThat(answerRequest(world.author().browser(), world.template(), second, "ACCEPT", null).status())
                .isEqualTo(200);

        assertThat(ids(queryRequests(world.approver().browser(), "{}"))).as("newest first by default")
                .containsExactly(second, first);
        assertThat(ids(queryRequests(world.approver().browser(), "{\"sort\":\"date\",\"descending\":false}")))
                .containsExactly(first, second);
        assertThat(ids(queryRequests(world.approver().browser(), "{\"sort\":\"status\",\"descending\":false}")))
                .as("accepted comes before open").containsExactly(second, first);
        assertThat(ids(queryRequests(world.approver().browser(), "{\"sort\":\"status\",\"descending\":true}")))
                .containsExactly(first, second);
        assertThat(ids(queryRequests(world.approver().browser(), "{\"status\":\"ACCEPTED\"}"))).containsExactly(second);
        assertThat(ids(queryRequests(world.approver().browser(), "{\"sort\":\"version\"}"))).hasSize(2);
        assertThat(queryRequests(world.approver().browser(), "{\"sort\":\"bogus\"}").status()).isEqualTo(400);
        assertThat(queryRequests(world.approver().browser(), "{\"status\":\"BOGUS\"}").status()).isEqualTo(400);
    }

    @Test
    void aPageCanBeShortAndTheCursorWalksTheWholeResultOnceWhateverTheSize() {
        World world = world();
        Set<String> all = new HashSet<>();
        for (int i = 0; i < 5; i++) {
            TestBrowser who = i % 2 == 0 ? world.reader().browser() : world.colleague().browser();
            all.add(asked(who, world.template(), "Wording " + i, 1));
        }

        Set<String> seen = new HashSet<>();
        List<String> order = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            Response page = queryRequests(world.approver().browser(),
                    "{\"size\":2" + (cursor == null ? "" : ",\"cursor\":\"" + cursor + "\"") + "}");
            order.addAll(ids(page));
            seen.addAll(ids(page));
            cursor = page.body().contains("\"nextCursor\"") ? JsonPath.read(page.body(), "$.data.nextCursor") : null;
            pages++;
        } while (cursor != null && pages < 20);

        assertThat(seen).isEqualTo(all);
        assertThat(order).as("no row twice").hasSameSizeAs(seen);
        assertThat(pages).isGreaterThanOrEqualTo(3);
        assertThat(queryRequests(world.approver().browser(), "{\"cursor\":\"not-a-cursor\"}").status())
                .isEqualTo(400);
        assertThat(queryRequests(world.approver().browser(),
                "{\"sort\":\"version\",\"cursor\":\"" + JsonPath.<String>read(queryRequests(
                        world.approver().browser(), "{\"size\":1}").body(), "$.data.nextCursor") + "\"}").status())
                .as("a cursor belongs to the order it was made for").isEqualTo(400);
    }

    @Test
    void aRequestOfATemplateThePersonCanNoLongerReadIsNotInTheirList() {
        World world = world();
        String request = asked(world.reader().browser(), world.template(), "Mine", 1);
        assertThat(ids(queryRequests(world.reader().browser(), "{}"))).containsExactly(request);

        assertThat(unpublish(world.approver().browser(), world.template()).status()).isEqualTo(200);

        assertThat(ids(queryRequests(world.reader().browser(), "{}")))
                .as("the request follows the template: no longer readable, no longer listed").isEmpty();
        assertThat(requestById(world.reader().browser(), request).status()).isEqualTo(404);
    }

    @Test
    void anotherOrganizationsRequestsAreNeverInTheListWhateverTheHeadersSay() {
        World mine = world();
        World theirs = world();
        String request = asked(mine.reader().browser(), mine.template(), "Mine", 1);
        String other = asked(theirs.reader().browser(), theirs.template(), "Theirs", 1);
        String[] forged = {"X-Tenant-Id", theirs.setting().organization().id().value().toString(),
            "X-Forwarded-Host", theirs.setting().organization().host(), "X-Organization",
            theirs.setting().organization().tenant().slug()};

        assertThat(ids(queryRequests(theirs.approver().browser(), "{}"))).containsExactly(other);
        assertThat(ids(queryRequests(mine.approver().browser(), "{}"))).containsExactly(request);
        Response forgedAsk = theirs.approver().browser().postJson(CHANGE_REQUESTS + "/query", "{}", forged);
        assertThat(ids(forgedAsk)).as("a forged organization changes nothing").containsExactly(other);
        assertThat(requestById(theirs.approver().browser(), request).status()).isEqualTo(404);
        assertThat(requestById(mine.approver().browser(), other).status()).isEqualTo(404);
    }

    @Test
    void aPlatformAdministratorOnThePlatformAddressAndSomeoneNotSignedInGetNothing() {
        World world = world();
        String request = asked(world.reader().browser(), world.template(), "Mine", 1);
        TestBrowser platform = TestPlatform.signedIn(port, TestPlatform.person(users, PlatformRole.PLATFORM_ADMIN));
        TestBrowser nobody = new TestBrowser(port, world.setting().organization().host());

        assertThat(queryRequests(platform, "{}").status()).isEqualTo(404);
        assertThat(requestById(platform, request).status()).isEqualTo(404);
        Response unauthenticated = queryRequests(nobody, "{}");
        assertThat(unauthenticated.status()).isEqualTo(401);
        assertThat(JsonPath.<String>read(unauthenticated.body(), "$.error.code")).isEqualTo("UNAUTHENTICATED");
        assertThat(requestById(nobody, request).status()).isEqualTo(401);
    }

    @Test
    void aQueryWithoutTheForgeryProtectionHeaderIsRefused() {
        World world = world();

        Response refused = world.reader().browser().postJsonWithoutCsrfHeader(CHANGE_REQUESTS + "/query", "{}");

        assertThat(refused.status()).isEqualTo(403);
    }

    @Test
    void oneRequestByItsIdentifierIsReachedByTheSamePeopleAsOnItsTemplate() {
        World world = world();
        String request = asked(world.reader().browser(), world.template(), "Mine", 1);

        assertThat(requestById(world.reader().browser(), request).status()).isEqualTo(200);
        assertThat(requestById(world.author().browser(), request).status()).isEqualTo(200);
        assertThat(requestById(world.approver().browser(), request).status()).isEqualTo(200);
        assertThat(requestById(world.colleague().browser(), request).status()).as("a colleague sees no one else's")
                .isEqualTo(404);
        assertThat(requestById(world.outsider().browser(), request).status()).isEqualTo(404);
        Response own = requestById(world.reader().browser(), request);
        assertThat(JsonPath.<String>read(own.body(), "$.data.templateId")).isEqualTo(world.template());
        assertThat(JsonPath.<String>read(own.body(), "$.data.templateName")).isNotBlank();
        assertThat(requestById(world.reader().browser(), "not-an-identifier").status()).isEqualTo(400);
    }

    @Test
    void theTemplateOwnerFollowsTheReviewOfARequestAndTheBellOpensItsPage() {
        Review review = review();
        String template = review.world().template();
        String request = JsonPath.read(raised(review.world().reader().browser(), template, "Walkway").body(),
                "$.data.id");

        Response view = requestById(review.world().author().browser(), request);

        assertThat(view.status()).as(view.body()).isEqualTo(200);
        String reviewId = reviewIdOf(view);
        assertThat(approvalPanel(review.world().author().browser(), reviewId).status())
                .as("the owner of the template can read the review record, so the flow shows").isEqualTo(200);
    }
}
