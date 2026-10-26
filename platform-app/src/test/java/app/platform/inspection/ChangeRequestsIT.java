package app.platform.inspection;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestHttp.Response;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * Change requests on a published template (FA5, ADR-0191; the controlled change of FA8, ADR-0202): a reader fills the
 * register and asks for a change, pointing at a row, a column, a cell or the whole template of the revision they read;
 * the owner and the approvers see the request and nobody else does; the approval declines it or the last approval
 * publishes the reviewer's revised copy; comments find their places by identifier in a later revision; a rejected copy
 * keeps its comments; and a request changes nothing of the template until it is published. The limits are set small
 * here so that they can be reached.
 */
@PlatformIntegrationTest
@TestPropertySource(properties = {
    "platform.outbox.enabled=true",
    "platform.workflow.enabled=false",
    "platform.inspection.change-requests.max-comments-per-request=5",
    "platform.inspection.change-requests.max-first-comments=4",
    "platform.inspection.change-requests.max-per-template=3"})
class ChangeRequestsIT extends ChangeRequestSupport {

    /** Takes a request of the one-step world to the end: the approver makes the copy and gives the last approval. */
    private static Response published(World world, String request) {
        copied(world.approver().browser(), world.template(), request);
        return approveWith(world.approver().browser(), approvalOfRequest(world, request), PUBLISH_CHOICE);
    }

    // ---- asking ----

    @Test
    void aReaderAsksForAChangeAndPointsAtEveryKindOfPlaceAndNothingOfTheTemplateChanges() {
        World world = world();
        String hash = JsonPath.read(version(world.reader().browser(), world.template(), 1).body(),
                "$.data.version.gridHash");

        Response made = ask(world.reader().browser(), world.template(), "The first row is wrong", 1,
                comment("r1", null, "This row"), comment(null, world.column(), "This column"),
                comment("r1", world.column(), "This cell"), comment(null, null, "In general"));

        assertThat(made.status()).as(made.body()).isEqualTo(201);
        assertThat(JsonPath.<List<String>>read(made.body(), "$.data.comments[*].kind"))
                .containsExactly("ROW", "COLUMN", "CELL", "TEMPLATE");
        assertThat(JsonPath.<String>read(made.body(), "$.data.comments[0].label")).isEqualTo("First");
        assertThat(JsonPath.<String>read(made.body(), "$.data.comments[2].label")).endsWith(" / First");
        assertThat(JsonPath.<Integer>read(made.body(), "$.data.placesTotal")).isEqualTo(3);
        assertThat(JsonPath.<Integer>read(made.body(), "$.data.placesInActive")).isEqualTo(3);
        assertThat(JsonPath.<String>read(made.body(), "$.data.status")).isEqualTo("OPEN");
        assertThat(JsonPath.<Boolean>read(made.body(), "$.data.mine")).isTrue();
        assertThat(JsonPath.<Boolean>read(made.body(), "$.data.canComment")).isTrue();
        assertThat(JsonPath.<Boolean>read(made.body(), "$.data.canCreateCopy")).as("the asker is no reviewer")
                .isFalse();
        assertThat(statuses(world.author().browser(), world.template())).as("no new version").containsExactly("ACTIVE");
        assertThat(JsonPath.<String>read(version(world.reader().browser(), world.template(), 1).body(),
                "$.data.version.gridHash")).as("the revision read is as it was").isEqualTo(hash);
        assertThat(audited(world.setting().organization(), "inspection.changerequest.created")).isEqualTo(1);
    }

    @Test
    void aRequestMayBeMadeWithNoCommentAndGetsThemLater() {
        World world = world();

        String request = asked(world.reader().browser(), world.template(), "Please look", 1);
        Response added = add(world.reader().browser(), world.template(), request,
                comment(null, world.column(), "This column is too narrow"));

        assertThat(added.status()).as(added.body()).isEqualTo(200);
        assertThat(JsonPath.<List<String>>read(added.body(), "$.data.comments[*].kind")).containsExactly("COLUMN");
    }

    @Test
    void aPlaceThatIsNotInTheRevisionReadAndARevisionNobodyMayReadAreRefusedWithoutRepeatingThem() {
        World world = world();

        Response nowhere = ask(world.reader().browser(), world.template(), "A title", 1,
                comment("zzzzzzzz", null, "Where?"));
        Response nonsense = ask(world.reader().browser(), world.template(), "A title", 99);

        assertThat(nowhere.status()).isEqualTo(400);
        assertThat(JsonPath.<List<String>>read(nowhere.body(), "$.error.fields['comments[0].place']")).hasSize(1);
        assertThat(nowhere.body()).doesNotContain("zzzzzzzz");
        assertThat(nonsense.status()).isEqualTo(400);
        assertThat(listedRequests(world.author().browser(), world.template())).isEmpty();
    }

    @Test
    void markupEmptyAndTooLongTextIsRefusedAndNeverRepeated() {
        World world = world();
        String longText = "x".repeat(2001);

        Response markup = ask(world.reader().browser(), world.template(), "<b>bold</b>", 1);
        Response empty = ask(world.reader().browser(), world.template(), "   ", 1);
        Response angle = ask(world.reader().browser(), world.template(), "Fine", 1,
                comment("r1", null, "<script>alert(1)</script>"));
        Response tooLong = ask(world.reader().browser(), world.template(), "Fine", 1,
                comment("r1", null, longText));
        Response control = ask(world.reader().browser(), world.template(), "Fine", 1,
                comment("r1", null, "bell \\u0007 here"));

        for (Response refused : List.of(markup, empty, angle, tooLong, control)) {
            assertThat(refused.status()).as(refused.body()).isEqualTo(400);
            assertThat(code(refused)).isEqualTo("VALIDATION_ERROR");
        }
        assertThat(markup.body()).doesNotContain("bold");
        assertThat(angle.body()).doesNotContain("script").doesNotContain("alert");
        assertThat(tooLong.body()).doesNotContain("xxxxxxxx");
        assertThat(listedRequests(world.author().browser(), world.template())).isEmpty();
    }

    @Test
    void aRequestWithoutANumberOrWithATooLongOneIsRefusedAndNamesTheBox() {
        World world = world();

        Response none = askWith(world.reader().browser(), world.template(), "{\"changeRequested\":\"Walkway\"}", 1);
        Response blank = askWith(world.reader().browser(), world.template(),
                "{\"requestNo\":\"  \",\"changeRequested\":\"Walkway\"}", 1);
        Response tooLong = askWith(world.reader().browser(), world.template(),
                "{\"requestNo\":\"" + "9".repeat(31) + "\",\"changeRequested\":\"Walkway\"}", 1);

        for (Response refused : List.of(none, blank, tooLong)) {
            assertThat(refused.status()).as(refused.body()).isEqualTo(400);
            assertThat(refused.body()).contains("register.requestNo");
        }
        assertThat(listedRequests(world.author().browser(), world.template())).isEmpty();
    }

    @Test
    void theNumberOfRequestsOfATemplateHoldsEvenWhenEachOneWasAnswered() {
        World world = world();
        for (int i = 0; i < 3; i++) {
            String request = asked(world.reader().browser(), world.template(), "Request " + i, 1);
            assertThat(decline(world, request, "Not now").status()).isEqualTo(200);
        }

        Response fourth = ask(world.reader().browser(), world.template(), "Four", 1);

        assertThat(fourth.status()).as(fourth.body()).isEqualTo(409);
        assertThat(message(fourth)).contains("most change requests");
    }

    @Test
    void theNumberOfCommentsOfARequestHolds() {
        World world = world();
        String first = asked(world.reader().browser(), world.template(), "One", 1);

        for (int i = 0; i < 5; i++) {
            assertThat(add(world.reader().browser(), world.template(), first, comment(null, null, "Note " + i))
                    .status()).isEqualTo(200);
        }
        Response sixth = add(world.reader().browser(), world.template(), first, comment(null, null, "Note 6"));
        assertThat(sixth.status()).isEqualTo(409);
        assertThat(message(sixth)).contains("most comments");
        assertThat(decline(world, first, "Enough").status()).isEqualTo(200);
        Response tooMany = ask(world.reader().browser(), world.template(), "Many", 1, comment(null, null, "1"),
                comment(null, null, "2"), comment(null, null, "3"), comment(null, null, "4"),
                comment(null, null, "5"));
        assertThat(tooMany.status()).as("a request starts with at most four comments").isIn(400, 409);
    }

    // ---- who sees what ----

    @Test
    void theRequesterTheOwnerAndAnApproverSeeARequestAndNoOtherReaderDoes() {
        World world = world();
        String request = asked(world.reader().browser(), world.template(), "Mine", 1, comment("r1", null, "Row"));

        assertThat(listedRequests(world.reader().browser(), world.template())).containsExactly(request);
        assertThat(listedRequests(world.author().browser(), world.template())).containsExactly(request);
        assertThat(listedRequests(world.approver().browser(), world.template())).containsExactly(request);
        assertThat(list(world.author().browser(), world.template()).body()).contains("\"canReview\":true");
        assertThat(list(world.reader().browser(), world.template()).body()).contains("\"canReview\":false");
        assertThat(listedRequests(world.colleague().browser(), world.template())).isEmpty();
        assertThat(one(world.colleague().browser(), world.template(), request).status()).isEqualTo(404);
        assertThat(add(world.colleague().browser(), world.template(), request, comment(null, null, "Hello"))
                .status()).isEqualTo(404);
        assertThat(registerOf(world.colleague().browser(), world.template(), request).status()).isEqualTo(404);
        assertThat(list(world.outsider().browser(), world.template()).status()).isEqualTo(404);
    }

    @Test
    void aRequestIsSeenOnlyWhileTheRequesterCanStillReadTheTemplate() {
        World world = world();
        String request = asked(world.reader().browser(), world.template(), "Mine", 1);
        assertThat(one(world.reader().browser(), world.template(), request).status()).isEqualTo(200);

        assertThat(unpublish(world.approver().browser(), world.template()).status()).isEqualTo(200);

        assertThat(one(world.reader().browser(), world.template(), request).status()).isEqualTo(404);
        assertThat(list(world.reader().browser(), world.template()).status()).isEqualTo(404);
        assertThat(listedRequests(world.author().browser(), world.template())).as("the owner still has it")
                .containsExactly(request);
    }

    // ---- comments, answers ----

    @Test
    void theRequesterAndTheOwnerTalkOnAnOpenRequestAndAClosedOneTakesNoMoreComments() {
        World world = world();
        String request = asked(world.reader().browser(), world.template(), "Talk", 1, comment("r1", null, "Why?"));

        Response reply = add(world.author().browser(), world.template(), request, comment("r1", null, "Because"));
        Response again = add(world.reader().browser(), world.template(), request, comment(null, null, "Thanks"));

        assertThat(reply.status()).as(reply.body()).isEqualTo(200);
        assertThat(again.status()).isEqualTo(200);
        Response seen = one(world.reader().browser(), world.template(), request);
        assertThat(JsonPath.<List<String>>read(seen.body(), "$.data.comments[*].text"))
                .containsExactly("Why?", "Because", "Thanks");
        assertThat(JsonPath.<List<Boolean>>read(seen.body(), "$.data.comments[*].mine"))
                .containsExactly(true, false, true);

        assertThat(decline(world, request, "Not now").status()).isEqualTo(200);
        Response closed = add(world.reader().browser(), world.template(), request, comment(null, null, "Please?"));
        assertThat(closed.status()).isEqualTo(409);
    }

    @Test
    void onlyTheApprovalDeclinesARequestAndADeclinedRequestIsFinal() {
        World world = world();
        String doomed = asked(world.reader().browser(), world.template(), "Doomed", 1);

        Response noReason = decide(world.approver().browser(), approvalOfRequest(world, doomed), "reject", " ");
        Response declined = decline(world, doomed, "Not our decision");
        Response again = decide(world.approver().browser(), approvalOfRequest(world, doomed), "reject", "Twice");

        assertThat(noReason.status()).as("a decline needs a reason").isGreaterThanOrEqualTo(400);
        assertThat(declined.status()).as(declined.body()).isEqualTo(200);
        assertThat(requestStatus(world.reader().browser(), world.template(), doomed)).isEqualTo("DECLINED");
        assertThat(again.status()).as("a declined request stays declined").isGreaterThanOrEqualTo(400);
        assertThat(requestStatus(world.reader().browser(), world.template(), doomed)).isEqualTo("DECLINED");
        assertThat(list(world.author().browser(), world.template()).body()).contains("\"openCount\":0");
        assertThat(ask(world.reader().browser(), world.template(), "A new one", 1).status())
                .as("a new change may be asked for").isEqualTo(201);
    }

    // ---- the revised copy and the publish ----

    @Test
    void theRevisedCopyIsACopyOfTheRevisionInUseAndOnlyThePeopleAskedToDecideMakeIt() {
        World world = world();
        String request = asked(world.reader().browser(), world.template(), "First", 1, comment("r1", null, "Row"));

        assertThat(createCopy(world.reader().browser(), world.template(), request).status()).isEqualTo(403);
        assertThat(createCopy(world.colleague().browser(), world.template(), request).status()).isEqualTo(404);
        int copy = copied(world.approver().browser(), world.template(), request);

        assertThat(copy).isEqualTo(2);
        assertThat(statuses(world.author().browser(), world.template())).containsExactly("DRAFT", "ACTIVE");
        Response linked = one(world.reader().browser(), world.template(), request);
        assertThat(JsonPath.<String>read(linked.body(), "$.data.status")).as("still being decided")
                .isEqualTo("OPEN");
        assertThat(gridOf(version(world.approver().browser(), world.template(), copy)))
                .as("a copy of the revision in use").isEqualTo(gridOf(version(world.approver().browser(),
                        world.template(), 1)));
        assertThat(version(world.reader().browser(), world.template(), copy).status())
                .as("the copy is not read by those who may only read").isEqualTo(404);
        assertThat(audited(world.setting().organization(), "inspection.changerequest.copied")).isEqualTo(1);
    }

    // ---- what happens to the comments ----

    @Test
    void commentsFindTheirPlacesByIdentifierInALaterRevisionAndSayWhenAPlaceIsGone() {
        World world = world();
        String request = asked(world.reader().browser(), world.template(), "Two places", 1,
                comment("r1", null, "The row"), comment(null, world.column(), "The column"));
        int copy = copied(world.approver().browser(), world.template(), request);
        changeCopy(world.approver().browser(), world.template(), copy);
        assertThat(approveWith(world.approver().browser(), approvalOfRequest(world, request), PUBLISH_CHOICE)
                .status()).isEqualTo(200);

        Response now = one(world.reader().browser(), world.template(), request);

        assertThat(JsonPath.<List<Boolean>>read(now.body(), "$.data.comments[*].inActiveVersion"))
                .as("the row was taken out, the column was kept").containsExactly(false, true);
        assertThat(JsonPath.<Integer>read(now.body(), "$.data.versionNo")).as("still the version it read").isEqualTo(1);
        assertThat(JsonPath.<Integer>read(now.body(), "$.data.placesInActive")).isEqualTo(1);
        assertThat(JsonPath.<String>read(now.body(), "$.data.comments[0].label")).as("the label when it was made")
                .isEqualTo("First");
        assertThat(JsonPath.<String>read(now.body(), "$.data.resultingVersionStatus")).isEqualTo("ACTIVE");
        assertThat(statuses(world.author().browser(), world.template())).containsExactly("ACTIVE", "SUPERSEDED");
    }

    @Test
    void aRequestOnAnOlderRevisionStaysAskableAboutThatRevisionAfterANewOneIsPublished() {
        World world = world();
        String before = asked(world.reader().browser(), world.template(), "Before", 1);
        assertThat(published(world, before).status()).isEqualTo(200);

        Response late = ask(world.reader().browser(), world.template(), "After", 1, comment(null, world.column(), "x"));

        assertThat(late.status()).as("the revision was in use, so it may still be asked about: " + late.body())
                .isEqualTo(201);
        assertThat(listedRequests(world.reader().browser(), world.template())).hasSize(2);
    }

    @Test
    void aRejectedCopyKeepsTheCommentsAndTheRequestIsFinal() {
        World world = world();
        String request = asked(world.reader().browser(), world.template(), "Keep me", 1,
                comment("r1", null, "The row"));
        copied(world.approver().browser(), world.template(), request);
        assertThat(decline(world, request, "No").status()).isEqualTo(200);

        Response kept = one(world.author().browser(), world.template(), request);

        assertThat(JsonPath.<String>read(kept.body(), "$.data.status")).isEqualTo("DECLINED");
        assertThat(JsonPath.<List<String>>read(kept.body(), "$.data.comments[*].text")).containsExactly("The row");
        assertThat(statuses(world.author().browser(), world.template())).containsExactly("REJECTED", "ACTIVE");
        assertThat(JsonPath.<Boolean>read(kept.body(), "$.data.canComment")).isFalse();
    }

    @Test
    void aRetiredTemplateTakesNoRequests() {
        World world = world();
        String request = asked(world.reader().browser(), world.template(), "Before", 1);
        assertThat(retire(world.approver().browser(), world.template()).status()).isEqualTo(200);

        Response late = ask(world.reader().browser(), world.template(), "After", 1);

        assertThat(late.status()).isEqualTo(409);
        assertThat(list(world.reader().browser(), world.template()).body()).contains("\"canRequest\":false");
        assertThat(one(world.reader().browser(), world.template(), request).status()).as("still readable")
                .isEqualTo(200);
    }

    // ---- being told ----

    @Test
    void theOwnerIsToldWhenARequestArrivesAndTheRequesterWhenItIsAnsweredAndNobodyIsToldOfTheirOwnAct() {
        World world = world();

        String declined = asked(world.reader().browser(), world.template(), "Other", 1);
        add(world.reader().browser(), world.template(), declined, comment(null, null, "More"));
        assertThat(decline(world, declined, "Not now").status()).isEqualTo(200);
        String done = asked(world.reader().browser(), world.template(), "News", 1);
        assertThat(published(world, done).status()).isEqualTo(200);

        List<String> ownerSaw = JsonPath.read(world.author().browser().get("/api/v1/notifications").body(),
                "$.data[?(@.kind=='CHANGE_REQUEST')].body");
        List<String> requesterSaw = JsonPath.read(world.reader().browser().get("/api/v1/notifications").body(),
                "$.data[?(@.kind=='CHANGE_REQUEST')].body");
        assertThat(ownerSaw.toString()).contains("a change was asked for").contains("a comment was added");
        assertThat(requesterSaw.toString()).contains("it was declined").contains("it was published as a new revision");
        assertThat(requesterSaw.toString()).as("nobody is told of their own act")
                .doesNotContain("a change was asked for");
        String notices = world.author().browser().get("/api/v1/notifications").body();
        assertThat(JsonPath.<List<String>>read(notices, "$.data[?(@.kind=='CHANGE_REQUEST')].href"))
                .as("a bell row opens the template on its Change requests tab, with the request open")
                .allMatch(href -> href.startsWith("/apps/inspection/s-templates?open=" + world.template()
                        + "&tab=change-requests&request="));
        assertThat(JsonPath.<List<String>>read(notices, "$.data[?(@.kind=='CHANGE_REQUEST')].category"))
                .containsOnly("REQUESTS");
    }

    @Test
    void aPersonWhoSwitchedTheRequestNoticesOffIsNotToldAndTheRequestStillHappens() {
        World world = world();
        Response off = world.author().browser().request("PUT", "/api/v1/notification-preferences",
                "{\"choices\":[{\"category\":\"REQUESTS\",\"channel\":\"IN_APP\",\"enabled\":false}]}");
        assertThat(off.status()).as(off.body()).isEqualTo(200);

        String request = asked(world.reader().browser(), world.template(), "Quiet", 1);

        assertThat(listedRequests(world.author().browser(), world.template())).containsExactly(request);
        List<String> ownerSaw = JsonPath.read(world.author().browser().get("/api/v1/notifications").body(),
                "$.data[?(@.kind=='CHANGE_REQUEST')].body");
        assertThat(ownerSaw).isEmpty();
    }
}
