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
 * Who may do what with the fill-ins (FF1, ADR-0192): every endpoint as the filler who owns the fill-in, a colleague who
 * holds the same policy, a person who holds the Reviewer policy, an approver, an author, a reader, a member who holds
 * no policy, a person asked to review, a person of another organization, a platform administrator, someone who is not
 * signed in, and someone who forges the organization. The matrix of the roles table (start, edit own, send, review,
 * edit after sending, see other people's) and the rule that nobody makes or changes a header by hand.
 */
@PlatformIntegrationTest
@TestPropertySource(properties = {"platform.outbox.enabled=true", "platform.workflow.enabled=false"})
class FillInsAccessIT extends FillSupport {

    /** Every read and every change of a fill-in, with one that exists. */
    private Map<String, Response> everything(TestBrowser browser, String template, String fill, long version) {
        Map<String, Response> answers = new LinkedHashMap<>();
        answers.put("options", browser.get(TEMPLATES + "/" + template + "/fill-in-options"));
        answers.put("get", fillView(browser, fill));
        answers.put("save", saveCells(browser, fill, version, cells(row("r1", "\"t\":\"x\""))));
        answers.put("list", browser.get(FILL_INS));
        answers.put("start", startFill(browser, template));
        return answers;
    }

    // ---- start: the Filler policy, on a template published to them ----

    @Test
    void onlyAFillerOfThePublishedDepartmentStartsAFillIn() {
        World world = world();
        Person author = world.author();
        Person reader = reader(world.setting());

        assertThat(startFill(world.filler().browser(), world.template()).status()).isEqualTo(201);
        assertThat(startFill(author.browser(), world.template()).status()).as("an author may not fill in")
                .isEqualTo(403);
        assertThat(startFill(world.approver().browser(), world.template()).status()).as("nor an approver")
                .isEqualTo(403);
        assertThat(startFill(reader.browser(), world.template()).status()).as("a reader cannot even see it")
                .isEqualTo(404);
        assertThat(startFill(world.reviewer().browser(), world.template()).status()).isEqualTo(404);
        assertThat(startFill(world.outsider().browser(), world.template()).status()).isEqualTo(404);
        assertThat(listedFills(world.approver().browser(), "")).hasSize(1);
    }

    @Test
    void aFillerWhoIsNotInThePublishedDepartmentCannotStartOne() {
        World world = world();
        Person elsewhere = holding(world.setting(), "Filler");

        Response options = elsewhere.browser().get(TEMPLATES + "/" + world.template() + "/fill-in-options");

        assertThat(options.status()).isEqualTo(404);
        assertThat(startFill(elsewhere.browser(), world.template()).status()).isEqualTo(404);
    }

    @Test
    void theOptionsTellTheFillerTheyMayAndTheApproverTheyMayNot() {
        World world = world();

        Response filler = world.filler().browser().get(TEMPLATES + "/" + world.template() + "/fill-in-options");
        Response approver = world.approver().browser().get(TEMPLATES + "/" + world.template() + "/fill-in-options");

        assertThat(JsonPath.<Boolean>read(filler.body(), "$.data.canStart")).isTrue();
        assertThat(JsonPath.<Integer>read(filler.body(), "$.data.activeVersion")).isEqualTo(1);
        assertThat(JsonPath.<Boolean>read(approver.body(), "$.data.canStart")).isFalse();
        assertThat(JsonPath.<String>read(approver.body(), "$.data.reason")).contains("right to fill in");
    }

    // ---- see, edit own, send ----

    @Test
    void theFillerWhoOwnsAFillInReadsSavesAndSendsIt() {
        World world = world();
        String fill = started(world.filler().browser(), world.template());
        long version = fillVersion(fillView(world.filler().browser(), fill));

        Map<String, Response> own = everything(world.filler().browser(), world.template(), fill, version);

        assertThat(own.get("options").status()).isEqualTo(200);
        assertThat(own.get("get").status()).isEqualTo(200);
        assertThat(own.get("save").status()).isEqualTo(200);
        assertThat(own.get("list").status()).isEqualTo(200);
        assertThat(own.get("start").status()).isEqualTo(201);
        assertThat(JsonPath.<List<String>>read(own.get("list").body(), "$.data.rows[*].fillId")).contains(fill);
    }

    @Test
    void aColleagueWhoHoldsTheSamePolicySeesNothingOfAnotherFillersFillIn() {
        World world = world();
        String fill = started(world.filler().browser(), world.template());
        long version = fillVersion(fillView(world.filler().browser(), fill));

        Map<String, Response> colleague = everything(world.colleague().browser(), world.template(), fill, version);

        assertThat(colleague.get("get").status()).isEqualTo(404);
        assertThat(colleague.get("save").status()).isEqualTo(404);
        assertThat(JsonPath.<List<String>>read(colleague.get("list").body(), "$.data.rows[*].fillId"))
                .doesNotContain(fill);
        assertThat(saved(world.filler().browser(), fill, complete())).as("the owner is not stopped").isPositive();
    }

    @Test
    void aPersonWhoHoldsTheReviewerPolicyReadsEveryFillInAndChangesNone() {
        World world = world();
        String fill = started(world.filler().browser(), world.template());
        long version = fillVersion(fillView(world.filler().browser(), fill));

        Map<String, Response> reviewer = everything(world.reviewer().browser(), world.template(), fill, version);

        assertThat(reviewer.get("get").status()).isEqualTo(200);
        assertThat(JsonPath.<List<String>>read(reviewer.get("list").body(), "$.data.rows[*].fillId")).contains(fill);
        assertThat(reviewer.get("save").status()).as("read, not change").isEqualTo(403);
        assertThat(reviewer.get("start").status()).isEqualTo(404);
        assertThat(sendFill(world.reviewer().browser(), fill, world.process()).status())
                .as("the reviewer does not send someone else's").isIn(403, 404);
    }

    @Test
    void anApproverSeesEveryFillInButDoesNotFillInOrSendSomeoneElses() {
        World world = world();
        String fill = started(world.filler().browser(), world.template());
        saved(world.filler().browser(), fill, complete());
        long version = fillVersion(fillView(world.approver().browser(), fill));

        Response draftSave = saveCells(world.approver().browser(), fill, version, complete());
        Response send = sendFill(world.approver().browser(), fill, world.process());

        assertThat(fillView(world.approver().browser(), fill).status()).isEqualTo(200);
        assertThat(JsonPath.<Boolean>read(fillView(world.approver().browser(), fill).body(), "$.data.canEdit"))
                .isFalse();
        assertThat(draftSave.status()).as("a draft is its filler's own work").isEqualTo(403);
        assertThat(send.status()).isEqualTo(409);
        assertThat(fillStatus(world.filler().browser(), fill)).isEqualTo("DRAFT");
    }

    @Test
    void anAuthorAReaderAndAMemberWithNoPolicyGetNotFoundForAFillIn() {
        World world = world();
        String fill = started(world.filler().browser(), world.template());
        long version = fillVersion(fillView(world.filler().browser(), fill));

        for (Person person : List.of(world.author(), reader(world.setting()), world.outsider())) {
            Map<String, Response> answers = everything(person.browser(), world.template(), fill, version);
            assertThat(answers.get("get").status()).isEqualTo(404);
            assertThat(answers.get("save").status()).isEqualTo(404);
            assertThat(answers.get("list").status()).isEqualTo(404);
        }
    }

    // ---- nobody makes or changes a header by hand ----

    @Test
    void theRecordScreensCannotMakeChangeOrRemoveAHeader() {
        World world = world();
        String fill = started(world.filler().browser(), world.template());
        TestBrowser filler = world.filler().browser();

        Response made = filler.postJson(FILL_RECORDS, "{\"fields\":{\"name\":\"By hand\"}}");
        Response renamed = filler.request("PATCH", FILL_RECORDS + "/" + fill, "{\"version\":"
                + JsonPath.<Number>read(filler.get(FILL_RECORDS + "/" + fill).body(), "$.data.version")
                + ",\"fields\":{\"templateId\":\"" + java.util.UUID.randomUUID() + "\",\"status\":\"APPROVED\"}}");
        Response removed = filler.request("DELETE", FILL_RECORDS + "/" + fill, null);

        assertThat(made.status()).as(made.body()).isIn(400, 403, 404);
        assertThat(renamed.status()).as(renamed.body()).isIn(400, 403, 404);
        assertThat(removed.status()).as(removed.body()).isIn(400, 403, 404, 409);
        assertThat(headerStatus(filler, fill)).isEqualTo("DRAFT");
        assertThat(headerField(filler, fill, "templateId")).isEqualTo(world.template());
        assertThat(listedFills(world.approver().browser(), "")).containsExactly(fill);
    }

    @Test
    void theRecordsOfFillInsAreKeptEvenForAnAdministrator() {
        World world = world();
        String fill = started(world.filler().browser(), world.template());

        Response removed = world.setting().admin().request("DELETE", FILL_RECORDS + "/" + fill, null);

        assertThat(removed.status()).isIn(400, 403, 404, 409);
        assertThat(fillStatus(world.filler().browser(), fill)).isEqualTo("DRAFT");
    }

    // ---- other organizations, forgery, platform, not signed in ----

    @Test
    void aPersonOfAnotherOrganizationReachesNothingEvenWithTheSameIdentifiers() {
        World mine = world();
        World theirs = world();
        String fill = started(mine.filler().browser(), mine.template());
        long version = fillVersion(fillView(mine.filler().browser(), fill));

        Map<String, Response> answers = everything(theirs.approver().browser(), mine.template(), fill, version);

        assertThat(answers.get("options").status()).isEqualTo(404);
        assertThat(answers.get("get").status()).isEqualTo(404);
        assertThat(answers.get("save").status()).isEqualTo(404);
        assertThat(answers.get("start").status()).isEqualTo(404);
        assertThat(JsonPath.<List<String>>read(answers.get("list").body(), "$.data.rows[*].fillId"))
                .doesNotContain(fill);
        assertThat(fillStatus(mine.filler().browser(), fill)).isEqualTo("DRAFT");
    }

    @Test
    void aForgedOrganizationInTheHeadersChangesNothing() {
        World mine = world();
        World theirs = world();
        String fill = started(mine.filler().browser(), mine.template());
        String[] forged = {"X-Tenant-Id", theirs.setting().organization().id().value().toString(),
            "X-Forwarded-Host", theirs.setting().organization().host(), "X-Organization",
            theirs.setting().organization().tenant().slug()};

        Response theirsReaching = theirs.approver().browser().get(FILLS + fill, forged);
        Response own = mine.filler().browser().get(FILLS + fill, forged);

        assertThat(theirsReaching.status()).isEqualTo(404);
        assertThat(own.status()).isEqualTo(200);
    }

    @Test
    void aPlatformAdministratorOnThePlatformAddressReachesNoFillIn() {
        World world = world();
        String fill = started(world.filler().browser(), world.template());
        long version = fillVersion(fillView(world.filler().browser(), fill));
        TestBrowser platform = TestPlatform.signedIn(port, TestPlatform.person(users, PlatformRole.PLATFORM_ADMIN));

        Map<String, Response> answers = everything(platform, world.template(), fill, version);

        answers.forEach((name, answer) -> assertThat(answer.status()).as(name + ": " + answer.body())
                .isEqualTo(404));
    }

    @Test
    void aPersonWhoIsNotSignedInGetsUnauthenticatedEverywhere() {
        World world = world();
        String fill = started(world.filler().browser(), world.template());
        TestBrowser nobody = new TestBrowser(port, world.setting().organization().host());

        Map<String, Response> answers = everything(nobody, world.template(), fill, 0);

        answers.forEach((name, answer) -> {
            assertThat(answer.status()).as(name + ": " + answer.body()).isEqualTo(401);
            assertThat(JsonPath.<String>read(answer.body(), "$.error.code")).isEqualTo("UNAUTHENTICATED");
        });
    }

    @Test
    void aStateChangingRequestWithoutTheForgeryProtectionHeaderIsRefusedAndChangesNothing() {
        World world = world();
        String fill = started(world.filler().browser(), world.template());

        Response start = world.filler().browser().postJsonWithoutCsrfHeader(TEMPLATES + "/" + world.template()
                + "/fill-ins", "{}");

        assertThat(start.status()).isEqualTo(403);
        assertThat(listedFills(world.filler().browser(), "")).containsExactly(fill);
        assertThat(JsonPath.<List<Object>>read(fillView(world.filler().browser(), fill).body(), "$.data.cells.rows"))
                .isEmpty();
    }

    @Test
    void anOrganizationWithoutTheFeatureHasNoFillInsAndTheyComeBackWithIt() {
        World world = world();
        String fill = started(world.filler().browser(), world.template());
        switchOff(world.setting().organization());

        assertThat(fillView(world.filler().browser(), fill).status()).isEqualTo(404);
        assertThat(world.filler().browser().get(FILL_INS).status()).isEqualTo(404);
        assertThat(startFill(world.filler().browser(), world.template()).status()).isEqualTo(404);

        switchOn(world.setting().organization());
        assertThat(fillView(world.filler().browser(), fill).status()).as("it all comes back").isEqualTo(200);
    }

    @Test
    void aBadIdentifierOrAMalformedBodyIsRefusedBeforeAnythingHappens() {
        World world = world();
        String fill = started(world.filler().browser(), world.template());
        long version = fillVersion(fillView(world.filler().browser(), fill));
        TestBrowser filler = world.filler().browser();

        assertThat(filler.get(FILLS + "not-an-id").status()).isEqualTo(400);
        assertThat(filler.get("/api/v1/sheet-fills/lower/" + fill).status()).isEqualTo(404);
        assertThat(filler.get("/api/v1/sheet-fills/Other/" + fill).status()).isEqualTo(404);
        assertThat(saveCells(filler, fill, version, "not json").status()).isEqualTo(400);
        assertThat(saveCells(filler, fill, version, "{\"formatVersion\":1}").status()).isEqualTo(400);
        assertThat(filler.request("PUT", FILLS + fill + "/cells", complete()).status()).isEqualTo(400);
        assertThat(filler.get(FILL_INS + "?templateId=nope").status()).isEqualTo(400);
        assertThat(filler.get(FILL_INS + "?status=SOMETHING").status()).isEqualTo(400);
        assertThat(JsonPath.<List<Object>>read(fillView(filler, fill).body(), "$.data.cells.rows")).isEmpty();
    }

    @Test
    void aBodyWithNoAnnouncedLengthIsBoundedToo() {
        World world = world();
        String fill = started(world.filler().browser(), world.template());
        long version = fillVersion(fillView(world.filler().browser(), fill));
        byte[] huge = cells(row("a", "\"t\":\"" + "x".repeat(1_100_000) + "\"")).getBytes(
                java.nio.charset.StandardCharsets.UTF_8);

        Response refused = world.filler().browser().chunked("PUT", FILLS + fill + "/cells?version=" + version, huge);

        assertThat(refused.status()).isEqualTo(413);
    }

    @Test
    void aBodyOverTheLimitIsRefusedBeforeItIsRead() {
        World world = world();
        String fill = started(world.filler().browser(), world.template());
        long version = fillVersion(fillView(world.filler().browser(), fill));
        String huge = cells(row("a", "\"t\":\"" + "x".repeat(1_100_000) + "\""));

        Response refused = saveCells(world.filler().browser(), fill, version, huge);

        assertThat(refused.status()).isEqualTo(413);
        assertThat(code(refused)).isEqualTo("PAYLOAD_TOO_LARGE");
    }
}
