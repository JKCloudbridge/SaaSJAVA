package app.platform.inspection;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.identity.PlatformRole;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestPlatform;
import com.jayway.jsonpath.JsonPath;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * Who may do what with the endpoints of the change requests (FA5, ADR-0191; the register and the revised copy of FA8,
 * ADR-0202): every endpoint as the reader who asked, a colleague who can read the template but made no request, the
 * author who owns the template, an approver who holds the open task, a member who holds no policy of the solution, a
 * person of another organization, a platform administrator, someone who is not signed in, and someone who forges the
 * organization. A request follows the template: it is reached by the person who asked and by those who may change the
 * template, and by nobody else; the revised copy and the bottom of the register are for the people asked to decide.
 */
@PlatformIntegrationTest
@TestPropertySource(properties = {"platform.outbox.enabled=true", "platform.workflow.enabled=false"})
class ChangeRequestsAccessIT extends ChangeRequestReviewSupport {

    /**
     * Every endpoint of a request that exists, in the order that lets the person who holds the task do everything: the
     * copy is made before the bottom of the register is completed.
     */
    private Map<String, Response> everything(TestBrowser browser, String template, String request, long version) {
        Map<String, Response> answers = new LinkedHashMap<>();
        answers.put("list", list(browser, template));
        answers.put("get", one(browser, template, request));
        answers.put("comment", add(browser, template, request, comment(null, null, "A note")));
        answers.put("copy", createCopy(browser, template, request));
        answers.put("register", registerOf(browser, template, request));
        answers.put("complete", completeRegister(browser, template, request, version, "\"department\":\"Plant 1\""));
        answers.put("file", browser.get(changes(template) + "/" + request + "/register/file"));
        answers.put("draw", browser.postJson(changes(template) + "/register/page", "{}"));
        answers.put("draft file", browser.postJson(changes(template) + "/register/file", "{}"));
        return answers;
    }

    private long versionOf(World world, String request) {
        return registerVersion(world.reader().browser(), world.template(), request);
    }

    @Test
    void theReaderWhoAskedMayReadTalkAndDrawTheRegisterAndNothingMore() {
        World world = world();
        String request = asked(world.reader().browser(), world.template(), "Mine", 1);

        Map<String, Response> reader = everything(world.reader().browser(), world.template(), request,
                versionOf(world, request));

        assertThat(reader.get("list").status()).isEqualTo(200);
        assertThat(reader.get("get").status()).isEqualTo(200);
        assertThat(reader.get("comment").status()).isEqualTo(200);
        assertThat(reader.get("copy").status()).as("the copy is for the people asked to decide").isEqualTo(403);
        assertThat(reader.get("register").status()).isEqualTo(200);
        assertThat(reader.get("complete").status()).as("the bottom is for the people asked to decide")
                .isEqualTo(403);
        assertThat(reader.get("file").status()).isEqualTo(200);
        assertThat(reader.get("draw").status()).isEqualTo(200);
        assertThat(reader.get("draft file").status()).isEqualTo(200);
        assertThat(requestStatus(world.reader().browser(), world.template(), request)).isEqualTo("OPEN");
    }

    @Test
    void aColleagueWhoCanReadTheTemplateMayDrawTheFormButSeesNoOneElsesRequest() {
        World world = world();
        String request = asked(world.reader().browser(), world.template(), "Theirs", 1);

        Map<String, Response> colleague = everything(world.colleague().browser(), world.template(), request,
                versionOf(world, request));

        assertThat(colleague.get("list").status()).isEqualTo(200);
        assertThat(JsonPath.<List<String>>read(colleague.get("list").body(), "$.data.requests[*].id")).isEmpty();
        for (String name : List.of("get", "comment", "copy", "register", "complete", "file")) {
            assertThat(colleague.get(name).status()).as(name + " finds nothing").isEqualTo(404);
        }
        assertThat(colleague.get("draw").status()).as("the blank form needs only the right to read").isEqualTo(200);
        assertThat(colleague.get("draft file").status()).isEqualTo(200);
        assertThat(ask(world.colleague().browser(), world.template(), "Mine too", 1).status())
                .as("only one change is in progress").isEqualTo(409);
        assertThat(requestStatus(world.reader().browser(), world.template(), request)).isEqualTo("OPEN");
    }

    @Test
    void anyoneWhoCanReadTheTemplateMayAskAndTheOutsiderCannot() {
        World world = world();

        Response outsider = ask(world.outsider().browser(), world.template(), "Not mine", 1);
        Response colleague = ask(world.colleague().browser(), world.template(), "Mine", 1);

        assertThat(outsider.status()).isEqualTo(404);
        assertThat(colleague.status()).as(colleague.body()).isEqualTo(201);
        assertThat(JsonPath.<Boolean>read(colleague.body(), "$.data.mine")).isTrue();
    }

    @Test
    void theAuthorWhoOwnsTheTemplateSeesEveryRequestAndOnlyTheApproverHoldingTheTaskDecides() {
        World world = world();
        String request = asked(world.reader().browser(), world.template(), "First", 1);
        long version = versionOf(world, request);

        Map<String, Response> author = everything(world.author().browser(), world.template(), request, version);
        Map<String, Response> approver = everything(world.approver().browser(), world.template(), request, version);

        assertThat(JsonPath.<List<String>>read(author.get("list").body(), "$.data.requests[*].id"))
                .contains(request);
        assertThat(author.get("get").status()).isEqualTo(200);
        assertThat(author.get("comment").status()).isEqualTo(200);
        assertThat(author.get("copy").status()).as("the author holds no task").isEqualTo(403);
        assertThat(author.get("complete").status()).isEqualTo(403);
        assertThat(author.get("register").status()).isEqualTo(200);
        assertThat(approver.get("list").status()).isEqualTo(200);
        assertThat(approver.get("get").status()).isEqualTo(200);
        assertThat(approver.get("comment").status()).isEqualTo(200);
        assertThat(approver.get("copy").status()).as(approver.get("copy").body()).isEqualTo(200);
        assertThat(approver.get("complete").status()).as(approver.get("complete").body()).isEqualTo(200);
    }

    @Test
    void theOrganizationAdministratorMayMakeTheCopyEvenWithoutATask() {
        World world = world();
        String request = asked(world.reader().browser(), world.template(), "First", 1);

        Response copy = createCopy(world.setting().admin(), world.template(), request);

        assertThat(copy.status()).as(copy.body()).isEqualTo(200);
    }

    @Test
    void anotherAuthorWhoDoesNotOwnTheTemplateMaySeeNoRequestOfIt() {
        World world = world();
        String request = asked(world.reader().browser(), world.template(), "Mine", 1);
        long version = versionOf(world, request);
        Person otherAuthor = author(world.setting());

        Map<String, Response> answers = everything(otherAuthor.browser(), world.template(), request, version);

        answers.forEach((name, answer) -> assertThat(answer.status()).as(name + ": " + answer.body())
                .isEqualTo(404));
    }

    @Test
    void aMemberWithNoPolicyOfTheSolutionGetsNotFoundEverywhere() {
        World world = world();
        String request = asked(world.reader().browser(), world.template(), "Mine", 1);
        long version = versionOf(world, request);

        Map<String, Response> outsider = everything(world.outsider().browser(), world.template(), request, version);

        outsider.forEach((name, answer) -> assertThat(answer.status()).as(name + ": " + answer.body())
                .isEqualTo(404));
    }

    @Test
    void aPersonOfAnotherOrganizationReachesNothingEvenWithTheSameIdentifiers() {
        World mine = world();
        World theirs = world();
        String request = asked(mine.reader().browser(), mine.template(), "Mine", 1);
        long version = versionOf(mine, request);

        Map<String, Response> answers = everything(theirs.approver().browser(), mine.template(), request, version);

        answers.forEach((name, answer) -> assertThat(answer.status()).as(name + ": " + answer.body())
                .isEqualTo(404));
        assertThat(requestStatus(mine.reader().browser(), mine.template(), request)).isEqualTo("OPEN");
        assertThat(listedRequests(theirs.approver().browser(), theirs.template())).isEmpty();
    }

    @Test
    void aForgedOrganizationInTheHeadersChangesNothing() {
        World mine = world();
        World theirs = world();
        String request = asked(mine.reader().browser(), mine.template(), "Mine", 1);
        String[] forged = {"X-Tenant-Id", theirs.setting().organization().id().value().toString(),
            "X-Forwarded-Host", theirs.setting().organization().host(), "X-Organization",
            theirs.setting().organization().tenant().slug()};

        Response theirTemplate = theirs.approver().browser().get(changes(mine.template()), forged);
        Response own = mine.reader().browser().get(changes(mine.template()) + "/" + request, forged);
        Response theirRegister = theirs.approver().browser().get(changes(mine.template()) + "/" + request
                + "/register", forged);

        assertThat(theirTemplate.status()).isEqualTo(404);
        assertThat(own.status()).isEqualTo(200);
        assertThat(theirRegister.status()).isEqualTo(404);
    }

    @Test
    void aPlatformAdministratorOnThePlatformAddressReachesNoRequest() {
        World world = world();
        String request = asked(world.reader().browser(), world.template(), "Mine", 1);
        long version = versionOf(world, request);
        TestBrowser platform = TestPlatform.signedIn(port, TestPlatform.person(users, PlatformRole.PLATFORM_ADMIN));

        Map<String, Response> answers = everything(platform, world.template(), request, version);

        answers.forEach((name, answer) -> assertThat(answer.status()).as(name).isEqualTo(404));
    }

    @Test
    void aPersonWhoIsNotSignedInGetsUnauthenticatedEverywhere() {
        World world = world();
        String request = asked(world.reader().browser(), world.template(), "Mine", 1);
        long version = versionOf(world, request);
        TestBrowser nobody = new TestBrowser(port, world.setting().organization().host());

        Map<String, Response> answers = everything(nobody, world.template(), request, version);

        answers.forEach((name, answer) -> {
            assertThat(answer.status()).as(name + ": " + answer.body()).isEqualTo(401);
            assertThat(JsonPath.<String>read(answer.body(), "$.error.code")).isEqualTo("UNAUTHENTICATED");
        });
    }

    @Test
    void aStateChangingRequestWithoutTheForgeryProtectionHeaderIsRefusedAndChangesNothing() {
        World world = world();

        Response refused = world.reader().browser().postJsonWithoutCsrfHeader(changes(world.template()),
                "{\"versionNo\":1,\"register\":" + register("Sneaky") + ",\"comments\":[]}");

        assertThat(refused.status()).isEqualTo(403);
        assertThat(listedRequests(world.author().browser(), world.template())).isEmpty();
    }

    @Test
    void anOrganizationWithoutTheFeatureHasNoRequestsAndTheyComeBackWithIt() {
        World world = world();
        String request = asked(world.reader().browser(), world.template(), "Mine", 1);
        long version = versionOf(world, request);
        switchOff(world.setting().organization());

        Map<String, Response> off = everything(world.setting().admin(), world.template(), request, version);
        off.forEach((name, answer) -> assertThat(answer.status()).as(name).isEqualTo(404));

        switchOn(world.setting().organization());
        assertThat(one(world.reader().browser(), world.template(), request).status()).as("it all comes back")
                .isEqualTo(200);
    }

    @Test
    void aRequestIsFoundByItsReviewRecordOnlyByThoseWhoMaySeeIt() {
        Review review = review();
        Response made = raised(review.world().reader().browser(), review.world().template(), "Mine");
        String reviewId = reviewIdOf(made);

        Response asker = review.world().reader().browser().get(CHANGE_REQUESTS + "/by-review/" + reviewId);
        Response colleague = review.world().colleague().browser().get(CHANGE_REQUESTS + "/by-review/" + reviewId);
        Response outsider = review.world().outsider().browser().get(CHANGE_REQUESTS + "/by-review/" + reviewId);

        assertThat(asker.status()).as(asker.body()).isEqualTo(200);
        assertThat(colleague.status()).isEqualTo(404);
        assertThat(outsider.status()).isEqualTo(404);
    }

    @Test
    void aBadIdentifierOrAMalformedBodyIsRefusedBeforeAnythingHappens() {
        World world = world();
        String request = asked(world.reader().browser(), world.template(), "Mine", 1);

        Response notAnId = world.reader().browser().get(TEMPLATES + "/not-an-id/change-requests");
        Response noRegister = world.reader().browser().postJson(changes(world.template()), "{\"versionNo\":1}");
        Response noVersion = world.reader().browser().postJson(changes(world.template()),
                "{\"register\":" + register("x") + "}");
        Response badStatus = world.reader().browser().get(changes(world.template()) + "?status=SOMETHING");
        Response notFound = world.author().browser().get(changes(world.template())
                + "/0198f5c0-0000-7000-8000-0000000000aa");
        Response noCopyFor = createCopy(world.approver().browser(), world.template(),
                "0198f5c0-0000-7000-8000-0000000000aa");

        assertThat(notAnId.status()).isEqualTo(400);
        assertThat(noRegister.status()).isEqualTo(400);
        assertThat(noVersion.status()).isEqualTo(400);
        assertThat(badStatus.status()).isEqualTo(400);
        assertThat(notFound.status()).isEqualTo(404);
        assertThat(noCopyFor.status()).isEqualTo(404);
        assertThat(requestStatus(world.reader().browser(), world.template(), request)).isEqualTo("OPEN");
    }
}
