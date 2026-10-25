package app.platform.inspection;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.BytesResponse;
import app.platform.testsupport.TestHttp.Response;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * A change request is one controlled change (FA8, ADR-0202): raising it fills the register and sends it to the process
 * the
 * organization set up (Plant QMS, Corporate QMS, Plant QMS again); a reviewer who is asked to decide makes the revised
 * copy
 * on demand and changes it; the last approval, which needs the Publisher policy and the copy, publishes the copy as the
 * next
 * revision; a rejection at any step ends the request for good and the copy is never the revision in use; only one
 * change is
 * in progress on a template; and the department people read the revision in use and nothing else.
 */
@PlatformIntegrationTest
@TestPropertySource(properties = {"platform.outbox.enabled=true", "platform.workflow.enabled=false"})
class ChangeRequestReviewIT extends ChangeRequestReviewSupport {

    private static final String OCTET = "application/octet-stream";

    /** A request raised by the reader of the review's world, with the identifiers a test needs. */
    private record Raised(String request, String reviewId) {
    }

    private static Raised raise(Review review) {
        Response made = raised(review.world().reader().browser(), review.world().template(), "Walkway wider",
                comment(null, null, "Please say 3 metres"));
        return new Raised(JsonPath.read(made.body(), "$.data.id"), reviewIdOf(made));
    }

    private static String activeRevision(TestBrowser browser, String template) {
        return JsonPath.read(lifecycle(browser, template).body(), "$.data.revision");
    }

    // ---- raising ----

    @Test
    void raisingARequestFillsThePlatformPartsOfTheRegisterAndAsksThePlantStep() {
        Review review = review();
        String template = review.world().template();
        Response made = askWith(review.world().reader().browser(), template, "{\"requestNo\":\"DCN-100\","
                + "\"changeRequested\":\"Walkway wider\",\"basisForChange\":\"Audit finding\","
                + "\"horizontalDeployment\":\"All plants\",\"obsoletedFormat\":\"Old form\","
                + "\"obsoletedEffectiveOn\":\"2026-12-31\"}", 1, comment(null, null, "Please say 3 metres"));
        assertThat(made.status()).as(made.body()).isEqualTo(201);
        String request = JsonPath.read(made.body(), "$.data.id");
        String reviewId = reviewIdOf(made);

        assertThat(JsonPath.<String>read(made.body(), "$.data.status")).isEqualTo("OPEN");
        assertThat(JsonPath.<String>read(made.body(), "$.data.title")).as("called by the first line").isEqualTo(
                "Walkway wider");
        Response register = registerOf(review.world().reader().browser(), template, request);
        assertThat(register.status()).as(register.body()).isEqualTo(200);
        assertThat(JsonPath.<String>read(register.body(), "$.data.requestNo")).isEqualTo("DCN-100");
        assertThat(JsonPath.<String>read(register.body(), "$.data.revNo")).as("the revision in use").isEqualTo("00");
        assertThat(JsonPath.<String>read(register.body(), "$.data.changeRequested")).isEqualTo("Walkway wider");
        assertThat(JsonPath.<String>read(register.body(), "$.data.obsoletedEffectiveOn")).isEqualTo("2026-12-31");
        assertThat(at(register.body(), "$.data.documentName")).as("the template name").isNotNull();
        assertThat(at(register.body(), "$.data.requestedOn")).isNotNull();
        assertThat(at(register.body(), "$.data.requestedBy")).as("the person who asked").isNotNull();
        assertThat(at(register.body(), "$.data.newRevNo")).as("nothing is published yet").isNull();
        assertThat(at(register.body(), "$.data.newRevisionAt")).isNull();
        assertThat(JsonPath.<Boolean>read(register.body(), "$.data.canComplete")).as("the asker only reads").isFalse();
        assertThat(at(register.body(), "$.data.grid.cells")).as("the Excel page").isNotNull();
        Response plant = approvalPanel(review.plant().browser(), reviewId);
        assertThat(JsonPath.<Boolean>read(plant.body(), "$.data.actions.canApprove")).as("Plant QMS holds the task")
                .isTrue();
        assertThat(JsonPath.<Boolean>read(approvalPanel(review.corporate().browser(), reviewId).body(),
                "$.data.actions.canApprove")).as("Corporate QMS is asked only at the second step").isFalse();
        assertThat(statusCopy(review.world().reader().browser(), reviewId)).isEqualTo("OPEN");
        assertThat(review.world().outsider().browser().get(REVIEW_RECORDS + "/" + reviewId).status())
                .as("the review record is private").isEqualTo(404);
    }

    @Test
    void onlyOneChangeIsInProgressOnATemplateAndTheWordsNameNothing() {
        Review review = review();
        String template = review.world().template();
        raise(review);

        Response second = ask(review.world().colleague().browser(), template, "Another", 1);

        assertThat(second.status()).as(second.body()).isEqualTo(409);
        assertThat(message(second)).contains("already in progress").doesNotContain("Walkway");
        Response listed = list(review.world().author().browser(), template);
        assertThat(JsonPath.<Boolean>read(listed.body(), "$.data.inProgress")).isTrue();
        assertThat(JsonPath.<Boolean>read(listed.body(), "$.data.canRequest")).isFalse();
        assertThat(listedRequests(review.world().author().browser(), template)).hasSize(1);
    }

    @Test
    void withoutAProcessForRequestsNoRequestCanBeRaisedAndTheWordsSayWhatToSetUp() {
        World world = world(false);

        Response refused = ask(world.reader().browser(), world.template(), "Walkway", 1);

        assertThat(refused.status()).as(refused.body()).isEqualTo(409);
        assertThat(message(refused)).contains("not set one up");
        assertThat(listedRequests(world.author().browser(), world.template())).as("nothing was made").isEmpty();
    }

    @Test
    void aProcessWhoseLastStepMayBeSkippedCannotStartARequestAndLeavesNothingBehind() {
        World world = world(false);
        String group = group(world.setting().admin(), "last-" + System.nanoTime(), world.approver());
        reviewProcess(world.setting().admin(), "Skippable" + System.nanoTime(),
                skippableStep("Last", groupApprover(group)));

        Response refused = ask(world.reader().browser(), world.template(), "Walkway", 1);

        assertThat(refused.status()).as(refused.body()).isEqualTo(409);
        assertThat(message(refused)).contains("cannot be skipped");
        assertThat(listedRequests(world.author().browser(), world.template())).isEmpty();
        assertThat(JsonPath.<List<String>>read(world.approver().browser().get(REVIEW_RECORDS).body(), "$.data[*].id"))
                .as("no review record was left").isEmpty();
    }

    @Test
    void aRequestIsRefusedWithTheEnginesWordsWhenNobodyCanDecideTheFirstStepAndLeavesNothingBehind() {
        World world = world(false);
        String empty = group(world.setting().admin(), "plant-empty-" + System.nanoTime());
        reviewProcess(world.setting().admin(), "Nobody" + System.nanoTime(), step("Plant", groupApprover(empty)));

        Response refused = ask(world.reader().browser(), world.template(), "Walkway", 1);

        assertThat(refused.status()).as(refused.body()).isEqualTo(409);
        assertThat(listedRequests(world.author().browser(), world.template())).as("no request was made").isEmpty();
    }

    // ---- the whole chain ----

    @Test
    void theFullChainPublishesTheRevisedSheetAsTheNextRevisionAndTheRegisterKeepsIt() {
        Review review = review();
        String template = review.world().template();
        TestBrowser plant = review.plant().browser();
        TestBrowser corporate = review.corporate().browser();
        Raised raised = raise(review);
        String approval = approvalOf(plant, raised.reviewId());

        assertThat(approve(plant, approval).status()).isEqualTo(200);
        assertThat(approveWith(corporate, approval, choices("location=PLANT1", "department=PLANT1/PRD")).status())
                .isEqualTo(200);
        Response early = approveWith(plant, approval, PUBLISH_CHOICE);
        assertThat(early.status()).as("the revised sheet has not been made").isEqualTo(409);
        assertThat(message(early)).contains("Create revised copy");
        assertThat(requestStatus(review.world().author().browser(), template, raised.request())).isEqualTo("OPEN");
        int copy = copied(plant, template, raised.request());
        assertThat(copy).isEqualTo(2);
        changeCopy(plant, template, copy);
        assertThat(statuses(review.world().reader().browser(), template)).as("the department reads the one in use")
                .containsExactly("ACTIVE");
        assertThat(version(review.world().reader().browser(), template, copy).status()).as("never the copy")
                .isEqualTo(404);

        Response last = approveWith(plant, approval, PUBLISH_CHOICE);

        assertThat(last.status()).as(last.body()).isEqualTo(200);
        assertThat(requestStatus(review.world().author().browser(), template, raised.request())).isEqualTo("DONE");
        assertThat(statusCopy(review.world().reader().browser(), raised.reviewId())).isEqualTo("DONE");
        assertThat(statuses(review.world().author().browser(), template)).containsExactly("ACTIVE", "SUPERSEDED");
        assertThat(statuses(review.world().reader().browser(), template)).as("after the publish too")
                .containsExactly("ACTIVE");
        assertThat(version(review.world().reader().browser(), template, 1).status()).as("the earlier revision")
                .isEqualTo(404);
        Response view = one(review.world().author().browser(), template, raised.request());
        assertThat(JsonPath.<Integer>read(view.body(), "$.data.resultingVersion")).isEqualTo(copy);
        assertThat(JsonPath.<Integer>read(view.body(), "$.data.resultingRevision")).as("00 then 01").isEqualTo(1);
        Response lifecycle = lifecycle(review.world().author().browser(), template);
        assertThat(JsonPath.<String>read(lifecycle.body(), "$.data.revision")).isEqualTo("01");
        assertThat(at(lifecycle.body(), "$.data.lastRevisedAt")).isNotNull();
        assertThat(JsonPath.<Integer>read(lifecycle.body(), "$.data.openChangeRequests")).as("the asterisk is gone")
                .isZero();
        assertThat(field(review.world().author().browser(), template, "revision")).as("saved on the template record")
                .isEqualTo("01");
        assertThat(field(review.world().author().browser(), template, "lastRevisedAt")).isNotNull();
        Response register = registerOf(review.world().reader().browser(), template, raised.request());
        assertThat(JsonPath.<String>read(register.body(), "$.data.newRevNo")).isEqualTo("01");
        assertThat(at(register.body(), "$.data.newRevisionAt")).isNotNull();
        assertThat(at(register.body(), "$.data.effectiveOn")).isNotNull();
        assertThat(JsonPath.<String>read(register.body(), "$.data.serverUpdated")).isEqualTo("YES");
        assertThat(JsonPath.<String>read(register.body(), "$.data.masterListUpdated")).isEqualTo("YES");
        assertThat(JsonPath.<String>read(register.body(), "$.data.signatureNote"))
                .startsWith("Approved electronically");
        assertThat(JsonPath.<String>read(register.body(), "$.data.approvedBy")).isNotBlank();
        assertThat(publications(review.world().setting().organization(), template, true)).as("published to PLANT1/PRD")
                .isEqualTo(1);
        assertThat(audited(review.world().setting().organization(), "inspection.changerequest.published")).isEqualTo(1);
    }

    @Test
    void theOptionsOfTheLastWindowAreSavedWithThePublish() {
        Review review = review();
        String template = review.world().template();
        TestBrowser plant = review.plant().browser();
        Raised raised = raise(review);
        String approval = approvalOf(plant, raised.reviewId());
        approve(plant, approval);
        approveWith(review.corporate().browser(), approval, choices("location=PLANT1"));
        copied(plant, template, raised.request());

        Response last = approveWith(plant, approval, choices("location=PLANT1", "department=PLANT1/PRD",
                "option=viewOnly"));

        assertThat(last.status()).as(last.body()).isEqualTo(200);
        Response lifecycle = lifecycle(review.world().author().browser(), template);
        assertThat(JsonPath.<Boolean>read(lifecycle.body(), "$.data.viewOnly")).isTrue();
        assertThat(JsonPath.<Boolean>read(lifecycle.body(), "$.data.allowDownload")).isFalse();
    }

    @Test
    void aSkippedMiddleStepStillEndsWithTheLastApprovalThatPublishes() {
        Review review = review(false, true);
        String template = review.world().template();
        TestBrowser plant = review.plant().browser();
        Raised raised = raise(review);
        String approval = approvalOf(plant, raised.reviewId());

        assertThat(approve(plant, approval).status()).isEqualTo(200);
        copied(plant, template, raised.request());
        Response last = approveWith(plant, approval, PUBLISH_CHOICE);

        assertThat(last.status()).as(last.body()).isEqualTo(200);
        assertThat(requestStatus(review.world().author().browser(), template, raised.request())).isEqualTo("DONE");
        assertThat(activeRevision(review.world().author().browser(), template)).isEqualTo("01");
    }

    @Test
    void theLastApprovalIsRefusedWithoutThePublisherPolicyAndNothingChanges() {
        Review review = review(true, true, false);
        String template = review.world().template();
        TestBrowser plant = review.plant().browser();
        Raised raised = raise(review);
        String approval = approvalOf(plant, raised.reviewId());
        approve(plant, approval);
        approveWith(review.corporate().browser(), approval, choices("location=PLANT1"));
        int copy = copied(plant, template, raised.request());

        Response last = approveWith(plant, approval, PUBLISH_CHOICE);

        assertThat(last.status()).as(last.body()).isEqualTo(409);
        assertThat(message(last)).contains("Publisher access policy");
        assertThat(requestStatus(review.world().author().browser(), template, raised.request())).isEqualTo("OPEN");
        assertThat(statuses(review.world().author().browser(), template)).containsExactly("DRAFT", "ACTIVE");
        assertThat(copy).isEqualTo(2);
        assertThat(activeRevision(review.world().author().browser(), template)).isEqualTo("00");
    }

    @Test
    void anAudienceThatCannotBePublishedToRefusesTheLastApprovalAndLeavesTheCopyADraft() {
        Review review = review();
        String template = review.world().template();
        TestBrowser plant = review.plant().browser();
        Raised raised = raise(review);
        String approval = approvalOf(plant, raised.reviewId());
        approve(plant, approval);
        approveWith(review.corporate().browser(), approval, choices("location=PLANT1"));
        copied(plant, template, raised.request());

        Response last = approveWith(plant, approval, choices("location=PLANT1", "department=PLANT1/QLT"));

        assertThat(last.status()).as("QLT has no group of its own").isIn(400, 409);
        assertThat(requestStatus(review.world().author().browser(), template, raised.request())).isEqualTo("OPEN");
        assertThat(statuses(review.world().author().browser(), template)).containsExactly("DRAFT", "ACTIVE");
    }

    // ---- a rejection or an end without the publish ----

    private void rejectedAt(int step) {
        Review review = review();
        String template = review.world().template();
        TestBrowser plant = review.plant().browser();
        TestBrowser corporate = review.corporate().browser();
        Raised raised = raise(review);
        String approval = approvalOf(plant, raised.reviewId());
        boolean copy = step > 0;
        if (step >= 1) {
            assertThat(approve(plant, approval).status()).isEqualTo(200);
            copied(plant, template, raised.request());
        }
        if (step >= 2) {
            assertThat(approveWith(corporate, approval, choices("location=PLANT1")).status()).isEqualTo(200);
        }

        TestBrowser rejecting = step == 1 ? corporate : plant;
        assertThat(reject(rejecting, approval).status()).isEqualTo(200);

        assertThat(requestStatus(review.world().reader().browser(), template, raised.request())).isEqualTo("DECLINED");
        assertThat(statusCopy(review.world().reader().browser(), raised.reviewId())).isEqualTo("DECLINED");
        assertThat(activeRevision(review.world().author().browser(), template)).as("the old revision stays")
                .isEqualTo("00");
        assertThat(statuses(review.world().author().browser(), template)).isEqualTo(copy
                ? List.of("REJECTED", "ACTIVE") : List.of("ACTIVE"));
        assertThat(statuses(review.world().reader().browser(), template)).containsExactly("ACTIVE");
        assertThat(JsonPath.<Integer>read(lifecycle(review.world().author().browser(), template).body(),
                "$.data.openChangeRequests")).as("the asterisk is gone").isZero();
        assertThat(JsonPath.<List<String>>read(approvalPanel(review.world().reader().browser(), raised.reviewId())
                .body(), "$.data.current.history[?(@.kind=='REJECTED')].comment"))
                .as("the reason stays with the approval").containsExactly("Not good enough");
        Response again = ask(review.world().reader().browser(), template, "A new request", 1);
        assertThat(again.status()).as("the person raises a new one: " + again.body()).isEqualTo(201);
    }

    @Test
    void aRejectionAtTheFirstStepDeclinesTheRequestAndThereIsNoCopyToEnd() {
        rejectedAt(0);
    }

    @Test
    void aRejectionAtTheCorporateStepEndsTheCopyAsRejectedAndTheOldRevisionStays() {
        rejectedAt(1);
    }

    @Test
    void aRejectionAtTheLastStepEndsTheCopyAsRejectedAndTheOldRevisionStays() {
        rejectedAt(2);
    }

    @Test
    void aRevisionNumberIsNotUsedByARejectedCopy() {
        Review review = review();
        String template = review.world().template();
        TestBrowser plant = review.plant().browser();
        Raised first = raise(review);
        String firstApproval = approvalOf(plant, first.reviewId());
        approve(plant, firstApproval);
        copied(plant, template, first.request());
        assertThat(reject(review.corporate().browser(), firstApproval).status()).isEqualTo(200);
        Raised second = raise(review);
        String approval = approvalOf(plant, second.reviewId());
        approve(plant, approval);
        approveWith(review.corporate().browser(), approval, choices("location=PLANT1"));
        int copy = copied(plant, template, second.request());

        assertThat(approveWith(plant, approval, PUBLISH_CHOICE).status()).isEqualTo(200);

        assertThat(copy).as("the rejected copy used version 2").isEqualTo(3);
        assertThat(activeRevision(review.world().author().browser(), template)).as("00, then 01: no gap")
                .isEqualTo("01");
    }

    @Test
    void aRequestWithdrawnFromItsReviewStaysOpenWithItsCopy() {
        Review review = review();
        String template = review.world().template();
        Raised raised = raise(review);
        String approval = approvalOf(review.plant().browser(), raised.reviewId());
        approve(review.plant().browser(), approval);
        copied(review.plant().browser(), template, raised.request());

        assertThat(recall(review.world().reader().browser(), approval).status()).isEqualTo(200);

        assertThat(requestStatus(review.world().reader().browser(), template, raised.request())).isEqualTo("OPEN");
        assertThat(statuses(review.world().author().browser(), template)).containsExactly("DRAFT", "ACTIVE");
    }

    // ---- who may make and change the revised copy ----

    @Test
    void onlyThePeopleAskedToDecideMakeAndChangeTheCopy() {
        Review review = review();
        String template = review.world().template();
        Raised raised = raise(review);

        assertThat(createCopy(review.corporate().browser(), template, raised.request()).status())
                .as("Corporate QMS has no task yet").isEqualTo(403);
        assertThat(createCopy(review.world().author().browser(), template, raised.request()).status())
                .as("the author may change the template and still has no task").isEqualTo(403);
        assertThat(createCopy(review.world().reader().browser(), template, raised.request()).status())
                .as("the person who asked").isEqualTo(403);
        Response made = createCopy(review.plant().browser(), template, raised.request());
        assertThat(made.status()).as(made.body()).isEqualTo(200);
        assertThat(createCopy(review.plant().browser(), template, raised.request()).status()).as("only once")
                .isEqualTo(409);
        int copy = JsonPath.<Integer>read(made.body(), "$.data.resultingVersion");
        Response read = version(review.plant().browser(), template, copy);
        assertThat(read.status()).isEqualTo(200);
        String grid = withoutRows(gridOf(read));

        assertThat(saveGrid(review.world().author().browser(), template, copy, rowVersion(read), grid).status())
                .as("the author is not asked to decide").isEqualTo(403);
        assertThat(saveGrid(review.corporate().browser(), template, copy, rowVersion(read), grid).status())
                .as("Corporate QMS is not asked yet").isEqualTo(403);
        assertThat(saveGrid(review.plant().browser(), template, copy, rowVersion(read), grid).status())
                .as("the person asked to decide").isEqualTo(200);
        assertThat(version(review.world().reader().browser(), template, copy).status()).as("never read by the asker")
                .isEqualTo(404);
        assertThat(version(review.world().outsider().browser(), template, copy).status()).isEqualTo(404);
    }

    @Test
    void aTaskHolderWhoCannotReadTheTemplateReadsAndChangesJustTheCopy() {
        Review review = review();
        String template = review.world().template();
        Raised raised = raise(review);
        int copy = copied(review.plant().browser(), template, raised.request());

        Response listed = review.plant().browser().get(versions(template));

        assertThat(listed.status()).isEqualTo(200);
        assertThat(JsonPath.<Boolean>read(listed.body(), "$.data.canChangeHeld")).isTrue();
        assertThat(JsonPath.<List<Integer>>read(listed.body(), "$.data.versions[?(@.held==true)].versionNo"))
                .containsExactly(copy);
    }

    @Test
    void anUploadReplacesTheGridOfTheCopyAndNeverStartsASecondDraft() {
        Review review = review();
        String template = review.world().template();
        TestBrowser plant = review.plant().browser();
        Raised raised = raise(review);
        int copy = copied(plant, template, raised.request());
        byte[] file = "Finding,Result\nDoor,ok\n".getBytes(StandardCharsets.UTF_8);

        BytesResponse plain = plant.bytes("POST", versions(template) + "/import?format=csv", file, "Content-Type",
                OCTET);
        BytesResponse replaced = plant.bytes("POST", versions(template) + "/import?format=csv&replace=true", file,
                "Content-Type", OCTET);

        assertThat(plain.status()).as("without replace it would be a second draft").isEqualTo(409);
        assertThat(replaced.status()).as(replaced.text()).isEqualTo(201);
        assertThat(JsonPath.<Integer>read(replaced.text(), "$.data.version.versionNo")).isEqualTo(copy);
        assertThat(statuses(review.world().author().browser(), template)).containsExactly("DRAFT", "ACTIVE");
        Response read = version(plant, template, copy);
        assertThat(JsonPath.<List<String>>read(read.body(), "$.data.grid.columns[*].label"))
                .containsExactly("Finding", "Result");
        assertThat(version(plant, template, 1).status()).as("the revision in use is never edited").isEqualTo(200);
    }

    @Test
    void aReplaceWithoutACopyOrFromSomeoneNotAskedIsRefused() {
        Review review = review();
        String template = review.world().template();
        byte[] file = "A,B\n1,2\n".getBytes(StandardCharsets.UTF_8);

        BytesResponse noCopy = review.plant().browser().bytes("POST",
                versions(template) + "/import?format=csv&replace=true", file, "Content-Type", OCTET);
        Raised raised = raise(review);
        copied(review.plant().browser(), template, raised.request());
        BytesResponse notAsked = review.world().author().browser().bytes("POST",
                versions(template) + "/import?format=csv&replace=true", file, "Content-Type", OCTET);

        assertThat(noCopy.status()).isEqualTo(409);
        assertThat(notAsked.status()).isEqualTo(403);
    }

    @Test
    void noNewDraftOfAPublishedTemplateIsStartedByHandInAnyWay() {
        World world = world();
        byte[] file = "A,B\n1,2\n".getBytes(StandardCharsets.UTF_8);

        Response blank = world.author().browser().postJson(versions(world.template()), "{}");
        Response copy = world.author().browser().postJson(versions(world.template()), "{\"basedOn\":1}");
        BytesResponse upload = world.author().browser().bytes("POST", versions(world.template())
                + "/import?format=csv", file, "Content-Type", OCTET);

        for (Response refused : new Response[] {blank, copy}) {
            assertThat(refused.status()).as(refused.body()).isEqualTo(409);
            assertThat(message(refused)).contains("change request");
        }
        assertThat(upload.status()).isEqualTo(409);
        assertThat(statuses(world.author().browser(), world.template())).containsExactly("ACTIVE");
    }

    // ---- the register ----

    @Test
    void thePeopleAskedToDecideCompleteTheBottomOfTheRegisterAndNobodyElseCan() {
        Review review = review();
        String template = review.world().template();
        Raised raised = raise(review);
        long version = registerVersion(review.plant().browser(), template, raised.request());
        String boxes = "\"department\":\"Plant 1\",\"approvedBy\":\"Plant QMS\",\"effectiveOn\":\"2026-12-31\","
                + "\"serverUpdated\":\"YES\",\"masterListUpdated\":\"NO\",\"newRevNo\":\"99\"";

        assertThat(completeRegister(review.world().reader().browser(), template, raised.request(), version, boxes)
                .status()).as("the person who asked").isEqualTo(403);
        assertThat(completeRegister(review.corporate().browser(), template, raised.request(), version, boxes)
                .status()).as("not asked yet").isEqualTo(403);
        Response saved = completeRegister(review.plant().browser(), template, raised.request(), version, boxes);

        assertThat(saved.status()).as(saved.body()).isEqualTo(200);
        assertThat(JsonPath.<String>read(saved.body(), "$.data.department")).isEqualTo("Plant 1");
        assertThat(JsonPath.<String>read(saved.body(), "$.data.effectiveOn")).isEqualTo("2026-12-31");
        assertThat(JsonPath.<String>read(saved.body(), "$.data.masterListUpdated")).isEqualTo("NO");
        assertThat(at(saved.body(), "$.data.newRevNo")).as("the new revision is the platform's").isNull();
        assertThat(completeRegister(review.plant().browser(), template, raised.request(), version, boxes).status())
                .as("a register read before the change").isEqualTo(409);
        assertThat(audited(review.world().setting().organization(),
                "inspection.changerequest.register.completed")).isEqualTo(1);
    }

    @Test
    void aWrongDayOrAnswerInTheBottomNamesTheBoxAndChangesNothing() {
        Review review = review();
        String template = review.world().template();
        Raised raised = raise(review);
        long version = registerVersion(review.plant().browser(), template, raised.request());

        Response refused = completeRegister(review.plant().browser(), template, raised.request(), version,
                "\"effectiveOn\":\"soon\",\"serverUpdated\":\"maybe\"");

        assertThat(refused.status()).isEqualTo(400);
        assertThat(refused.body()).contains("effectiveOn").contains("serverUpdated").doesNotContain("soon");
        assertThat(registerVersion(review.plant().browser(), template, raised.request())).isEqualTo(version);
    }

    @Test
    void theRegisterIsFinalOnceTheRequestIsAnswered() {
        Review review = review();
        String template = review.world().template();
        Raised raised = raise(review);
        String approval = approvalOf(review.plant().browser(), raised.reviewId());
        assertThat(reject(review.plant().browser(), approval).status()).isEqualTo(200);
        long version = registerVersion(review.plant().browser(), template, raised.request());

        Response late = completeRegister(review.plant().browser(), template, raised.request(), version,
                "\"department\":\"Late\"");

        assertThat(late.status()).as(late.body()).isEqualTo(409);
    }

    @Test
    void theRegisterAsAnExcelFileHoldsTheSameValuesAndNeverAFormula() {
        Review review = review();
        String template = review.world().template();
        Response made = askWith(review.world().reader().browser(), template,
                "{\"requestNo\":\"DCN-7\",\"changeRequested\":\"=1+1\"}", 1);
        String request = JsonPath.read(made.body(), "$.data.id");

        BytesResponse file = review.world().reader().browser().bytes("GET",
                changes(template) + "/" + request + "/register/file", new byte[0]);

        assertThat(file.status()).isEqualTo(200);
        assertThat(file.headers().get("content-type").get(0)).contains("spreadsheetml");
        assertThat(new String(file.body(), StandardCharsets.ISO_8859_1)).startsWith("PK");
    }

    // ---- who sees it ----

    @Test
    void anotherOrganizationSeesNoRequestNoRegisterAndNoCopy() {
        Review review = review();
        String template = review.world().template();
        Raised raised = raise(review);
        World other = world();

        assertThat(one(other.author().browser(), template, raised.request()).status()).isEqualTo(404);
        assertThat(registerOf(other.author().browser(), template, raised.request()).status()).isEqualTo(404);
        assertThat(createCopy(other.approver().browser(), template, raised.request()).status()).isEqualTo(404);
        assertThat(requestById(other.author().browser(), raised.request()).status()).isEqualTo(404);
    }

    @Test
    void theAsteriskCountIsRightWhileTheRequestIsInProgress() {
        Review review = review();
        String template = review.world().template();
        raise(review);

        assertThat(JsonPath.<Integer>read(lifecycle(review.world().author().browser(), template).body(),
                "$.data.openChangeRequests")).isEqualTo(1);
        assertThat(JsonPath.<Integer>read(lifecycle(review.world().reader().browser(), template).body(),
                "$.data.openChangeRequests")).as("the person who asked").isEqualTo(1);
        assertThat(JsonPath.<Integer>read(lifecycle(review.world().colleague().browser(), template).body(),
                "$.data.openChangeRequests")).as("a colleague who asked nothing").isZero();
    }
}
