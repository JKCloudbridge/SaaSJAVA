package app.platform.inspection;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.identity.PlatformRole;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestPlatform;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * Submit on a fill-in (FA6A, ADR-0196): with no approval process for fill-ins it saves nothing more than the cells,
 * ends the fill-in as Submitted for good (read-only, the status on the header follows) with the same checks as a review
 * (a required value, a row where the sheet needs one); with a process it changes nothing and says a review is needed,
 * so it is never a way round one. A fill-in sent for good stays final when a process is made afterwards. Who may: the
 * person who started it, nobody else, nobody from another organization, nobody who is not signed in.
 */
@PlatformIntegrationTest
@TestPropertySource(properties = {"platform.outbox.enabled=true", "platform.workflow.enabled=false"})
class FillInSubmitIT extends FillSupport {

    private static Response submitNow(TestBrowser browser, String fill) {
        return browser.postJson(FILL_INS + "/" + fill + "/submit", "{}");
    }

    @Test
    void withNoProcessSubmitChecksTheCellsThenEndsTheFillInAsSubmittedAndReadOnly() {
        World world = world(2, "FIXED_AND_REPEATABLE", false);
        String fill = started(world.filler().browser(), world.template());

        Response incomplete = submitNow(world.filler().browser(), fill);

        assertThat(incomplete.status()).isEqualTo(409);
        assertThat(JsonPath.<String>read(incomplete.body(), "$.error.message")).contains("required");
        assertThat(fillStatus(world.filler().browser(), fill)).isEqualTo("DRAFT");
        assertThat(headerStatus(world.filler().browser(), fill)).isEqualTo("DRAFT");

        saved(world.filler().browser(), fill, complete());
        Response sent = submitNow(world.filler().browser(), fill);

        assertThat(sent.status()).as(sent.body()).isEqualTo(200);
        assertThat(JsonPath.<String>read(sent.body(), "$.data.outcome")).isEqualTo("SUBMITTED");
        assertThat(fillStatus(world.filler().browser(), fill)).isEqualTo("SUBMITTED");
        assertThat(headerStatus(world.filler().browser(), fill)).isEqualTo("SUBMITTED");
        long version = fillVersion(fillView(world.filler().browser(), fill));
        assertThat(saveCells(world.filler().browser(), fill, version, complete()).status())
                .as("the cells are final").isGreaterThanOrEqualTo(400);
        assertThat(submitNow(world.filler().browser(), fill).status()).as("not twice").isEqualTo(409);
        assertThat(listedFills(world.approver().browser(), "status=SUBMITTED")).contains(fill);
    }

    @Test
    void aFillInWithNoRowsIsNotSentWhereTheSheetNeedsOne() {
        World world = world(1, "REPEATABLE", false);
        String fill = started(world.filler().browser(), world.template());

        Response sent = submitNow(world.filler().browser(), fill);

        assertThat(sent.status()).isEqualTo(409);
        assertThat(JsonPath.<String>read(sent.body(), "$.error.message")).contains("no rows");
        assertThat(fillStatus(world.filler().browser(), fill)).isEqualTo("DRAFT");
    }

    @Test
    void withAProcessSubmitChangesNothingAndSaysAReviewIsNeeded() {
        World world = world();
        String fill = started(world.filler().browser(), world.template());
        saved(world.filler().browser(), fill, complete());

        Response answer = submitNow(world.filler().browser(), fill);

        assertThat(answer.status()).isEqualTo(200);
        assertThat(JsonPath.<String>read(answer.body(), "$.data.outcome")).isEqualTo("REVIEW_NEEDED");
        assertThat(fillStatus(world.filler().browser(), fill)).isEqualTo("DRAFT");
        assertThat(headerStatus(world.filler().browser(), fill)).isEqualTo("DRAFT");
        assertThat(sendFill(world.filler().browser(), fill, world.process()).status()).as("the review is the way")
                .isEqualTo(201);
        assertThat(fillStatus(world.filler().browser(), fill)).isEqualTo("SUBMITTED");
    }

    @Test
    void aFillInSentForGoodStaysFinalWhenAProcessIsMadeAfterwards() {
        World world = world(2, "FIXED_AND_REPEATABLE", false);
        String fill = started(world.filler().browser(), world.template());
        saved(world.filler().browser(), fill, complete());
        assertThat(submitNow(world.filler().browser(), fill).status()).isEqualTo(200);

        String process = fillProcess(world.setting().admin(), "Later", step("Review", user(world.approver().userId())));
        Response sentAgain = sendFill(world.filler().browser(), fill, process);

        assertThat(sentAgain.status()).isEqualTo(409);
        assertThat(fillStatus(world.filler().browser(), fill)).isEqualTo("SUBMITTED");
        Response again = submitNow(world.filler().browser(), fill);
        assertThat(again.status()).as("a final fill-in is not sent again, with or without a process").isIn(200, 409);
        assertThat(fillStatus(world.filler().browser(), fill)).isEqualTo("SUBMITTED");
    }

    @Test
    void onlyThePersonWhoStartedItSubmitsIt() {
        World world = world(2, "FIXED_AND_REPEATABLE", false);
        String fill = started(world.filler().browser(), world.template());
        saved(world.filler().browser(), fill, complete());

        assertThat(submitNow(world.colleague().browser(), fill).status()).as("a colleague sees nothing").isEqualTo(404);
        assertThat(submitNow(world.outsider().browser(), fill).status()).isEqualTo(404);
        assertThat(submitNow(world.reviewer().browser(), fill).status()).as("read, not change").isEqualTo(403);
        assertThat(submitNow(world.approver().browser(), fill).status()).as("an approver may see, not send")
                .isGreaterThanOrEqualTo(403);
        assertThat(fillStatus(world.filler().browser(), fill)).isEqualTo("DRAFT");
        assertThat(submitNow(world.filler().browser(), fill).status()).isEqualTo(200);
    }

    @Test
    void aPersonOfAnotherOrganizationAForgedOrganizationAPlatformAdministratorAndNobodyReachNothing() {
        World mine = world(2, "FIXED_AND_REPEATABLE", false);
        World theirs = world(2, "FIXED_AND_REPEATABLE", false);
        String fill = started(mine.filler().browser(), mine.template());
        saved(mine.filler().browser(), fill, complete());
        String[] forged = {"X-Tenant-Id", theirs.setting().organization().id().value().toString(),
            "X-Forwarded-Host", theirs.setting().organization().host(), "X-Organization",
            theirs.setting().organization().tenant().slug()};

        Response elsewhere = submitNow(theirs.approver().browser(), fill);
        Response forgedBy = theirs.approver().browser().postJson(FILL_INS + "/" + fill + "/submit", "{}", forged);
        TestBrowser platform = TestPlatform.signedIn(port, TestPlatform.person(users, PlatformRole.PLATFORM_ADMIN));
        Response admin = platform.postJson(FILL_INS + "/" + fill + "/submit", "{}");
        TestBrowser nobody = new TestBrowser(port, mine.setting().organization().host());
        Response unknown = nobody.postJson(FILL_INS + "/" + fill + "/submit", "{}");

        assertThat(elsewhere.status()).isEqualTo(404);
        assertThat(forgedBy.status()).isEqualTo(404);
        assertThat(admin.status()).as("the platform host knows no organization's fill-in").isIn(403, 404);
        assertThat(unknown.status()).isEqualTo(401);
        assertThat(fillStatus(mine.filler().browser(), fill)).isEqualTo("DRAFT");
    }
}
