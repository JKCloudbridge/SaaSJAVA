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
 * Who may do what with the endpoints of the change requests (FA5, ADR-0191): every endpoint as the reader who asked, a
 * colleague who can read the template but made no request, the author who owns the template, an approver, a member who
 * holds no policy of the solution, a person of another organization, a platform administrator, someone who is not
 * signed in, and someone who forges the organization. A request follows the template: it is reached by the person who
 * asked and by those who may change the template, and by nobody else.
 */
@PlatformIntegrationTest
@TestPropertySource(properties = {"platform.outbox.enabled=true", "platform.workflow.enabled=false"})
class ChangeRequestsAccessIT extends ChangeRequestSupport {

    /** Every endpoint, with a request that exists; a changing call uses the request given so a test can choose. */
    private Map<String, Response> everything(TestBrowser browser, String template, String request) {
        Map<String, Response> answers = new LinkedHashMap<>();
        answers.put("list", list(browser, template));
        answers.put("get", one(browser, template, request));
        answers.put("create", ask(browser, template, "Another", 1));
        answers.put("comment", add(browser, template, request, comment(null, null, "A note")));
        answers.put("answer", answerRequest(browser, template, request, "ACCEPT", null));
        answers.put("turn", turn(browser, template, request));
        return answers;
    }

    @Test
    void theReaderWhoAskedMayAskAndTalkAndNothingMore() {
        World world = world();
        String request = asked(world.reader().browser(), world.template(), "Mine", 1);

        Map<String, Response> reader = everything(world.reader().browser(), world.template(), request);

        assertThat(reader.get("list").status()).isEqualTo(200);
        assertThat(reader.get("get").status()).isEqualTo(200);
        assertThat(reader.get("create").status()).isEqualTo(201);
        assertThat(reader.get("comment").status()).isEqualTo(200);
        assertThat(reader.get("answer").status()).as("answering is for those who may change the template")
                .isEqualTo(403);
        assertThat(reader.get("turn").status()).isEqualTo(403);
        assertThat(requestStatus(world.reader().browser(), world.template(), request)).isEqualTo("OPEN");
    }

    @Test
    void aColleagueWhoCanReadTheTemplateMayAskButSeesNoOneElsesRequest() {
        World world = world();
        String request = asked(world.reader().browser(), world.template(), "Theirs", 1);

        Map<String, Response> colleague = everything(world.colleague().browser(), world.template(), request);

        assertThat(colleague.get("list").status()).isEqualTo(200);
        assertThat(JsonPath.<List<String>>read(colleague.get("list").body(), "$.data.requests[*].id")).isEmpty();
        assertThat(colleague.get("create").status()).isEqualTo(201);
        assertThat(colleague.get("get").status()).isEqualTo(404);
        assertThat(colleague.get("comment").status()).isEqualTo(404);
        assertThat(colleague.get("answer").status()).isEqualTo(404);
        assertThat(colleague.get("turn").status()).as("not even to learn that it exists").isEqualTo(403);
        assertThat(requestStatus(world.reader().browser(), world.template(), request)).isEqualTo("OPEN");
    }

    @Test
    void theAuthorWhoOwnsTheTemplateAndAnApproverMaySeeAndAnswerEveryRequest() {
        World world = world();
        String first = asked(world.reader().browser(), world.template(), "First", 1);
        String second = asked(world.colleague().browser(), world.template(), "Second", 1);

        Map<String, Response> author = everything(world.author().browser(), world.template(), first);
        Map<String, Response> approver = everything(world.approver().browser(), world.template(), second);

        assertThat(JsonPath.<List<String>>read(author.get("list").body(), "$.data.requests[*].id"))
                .contains(first, second);
        assertThat(author.get("get").status()).isEqualTo(200);
        assertThat(author.get("create").status()).as("an author may also ask about a template").isEqualTo(201);
        assertThat(author.get("comment").status()).isEqualTo(200);
        assertThat(author.get("answer").status()).isEqualTo(200);
        assertThat(author.get("turn").status()).as("an accepted request can be turned").isEqualTo(200);
        assertThat(approver.get("list").status()).isEqualTo(200);
        assertThat(approver.get("get").status()).isEqualTo(200);
        assertThat(approver.get("comment").status()).isEqualTo(200);
        assertThat(approver.get("answer").status()).isEqualTo(200);
        assertThat(approver.get("turn").status()).as("the draft already exists and is joined").isEqualTo(200);
    }

    @Test
    void anotherAuthorWhoDoesNotOwnTheTemplateMaySeeNoRequestOfIt() {
        World world = world();
        String request = asked(world.reader().browser(), world.template(), "Mine", 1);
        Person otherAuthor = author(world.setting());

        Map<String, Response> answers = everything(otherAuthor.browser(), world.template(), request);

        answers.forEach((name, answer) -> assertThat(answer.status()).as(name + ": " + answer.body()).isEqualTo(404));
    }

    @Test
    void aMemberWithNoPolicyOfTheSolutionGetsNotFoundEverywhere() {
        World world = world();
        String request = asked(world.reader().browser(), world.template(), "Mine", 1);

        Map<String, Response> outsider = everything(world.outsider().browser(), world.template(), request);

        outsider.forEach((name, answer) -> assertThat(answer.status()).as(name + ": " + answer.body())
                .isEqualTo(404));
    }

    @Test
    void aPersonOfAnotherOrganizationReachesNothingEvenWithTheSameIdentifiers() {
        World mine = world();
        World theirs = world();
        String request = asked(mine.reader().browser(), mine.template(), "Mine", 1);

        Map<String, Response> answers = everything(theirs.approver().browser(), mine.template(), request);

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

        assertThat(theirTemplate.status()).isEqualTo(404);
        assertThat(own.status()).isEqualTo(200);
    }

    @Test
    void aPlatformAdministratorOnThePlatformAddressReachesNoRequest() {
        World world = world();
        String request = asked(world.reader().browser(), world.template(), "Mine", 1);
        TestBrowser platform = TestPlatform.signedIn(port, TestPlatform.person(users, PlatformRole.PLATFORM_ADMIN));

        Map<String, Response> answers = everything(platform, world.template(), request);

        answers.forEach((name, answer) -> assertThat(answer.status()).as(name).isEqualTo(404));
    }

    @Test
    void aPersonWhoIsNotSignedInGetsUnauthenticatedEverywhere() {
        World world = world();
        String request = asked(world.reader().browser(), world.template(), "Mine", 1);
        TestBrowser nobody = new TestBrowser(port, world.setting().organization().host());

        Map<String, Response> answers = everything(nobody, world.template(), request);

        answers.forEach((name, answer) -> {
            assertThat(answer.status()).as(name + ": " + answer.body()).isEqualTo(401);
            assertThat(JsonPath.<String>read(answer.body(), "$.error.code")).isEqualTo("UNAUTHENTICATED");
        });
    }

    @Test
    void aStateChangingRequestWithoutTheForgeryProtectionHeaderIsRefusedAndChangesNothing() {
        World world = world();

        Response refused = world.reader().browser().postJsonWithoutCsrfHeader(changes(world.template()),
                "{\"title\":\"Sneaky\",\"versionNo\":1,\"comments\":[]}");

        assertThat(refused.status()).isEqualTo(403);
        assertThat(listedRequests(world.author().browser(), world.template())).isEmpty();
    }

    @Test
    void anOrganizationWithoutTheFeatureHasNoRequestsAndTheyComeBackWithIt() {
        World world = world();
        String request = asked(world.reader().browser(), world.template(), "Mine", 1);
        switchOff(world.setting().organization());

        Map<String, Response> off = everything(world.setting().admin(), world.template(), request);
        off.forEach((name, answer) -> assertThat(answer.status()).as(name).isEqualTo(404));

        switchOn(world.setting().organization());
        assertThat(one(world.reader().browser(), world.template(), request).status()).as("it all comes back")
                .isEqualTo(200);
    }

    @Test
    void aBadIdentifierOrAMalformedBodyIsRefusedBeforeAnythingHappens() {
        World world = world();
        String request = asked(world.reader().browser(), world.template(), "Mine", 1);

        Response notAnId = world.reader().browser().get(TEMPLATES + "/not-an-id/change-requests");
        Response noTitle = world.reader().browser().postJson(changes(world.template()), "{\"versionNo\":1}");
        Response noVersion = world.reader().browser().postJson(changes(world.template()), "{\"title\":\"x\"}");
        Response badAction = answerRequest(world.author().browser(), world.template(), request, "ERASE", null);
        Response badStatus = world.reader().browser().get(changes(world.template()) + "?status=SOMETHING");
        Response noIds = world.author().browser().postJson(changes(world.template()) + "/turn-into-version",
                "{\"requestIds\":[]}");
        Response notFound = world.author().browser().get(changes(world.template())
                + "/0198f5c0-0000-7000-8000-0000000000aa");

        assertThat(notAnId.status()).isEqualTo(400);
        assertThat(noTitle.status()).isEqualTo(400);
        assertThat(noVersion.status()).isEqualTo(400);
        assertThat(badAction.status()).isEqualTo(400);
        assertThat(badStatus.status()).isEqualTo(400);
        assertThat(noIds.status()).isEqualTo(400);
        assertThat(notFound.status()).isEqualTo(404);
        assertThat(requestStatus(world.reader().browser(), world.template(), request)).isEqualTo("OPEN");
    }
}
