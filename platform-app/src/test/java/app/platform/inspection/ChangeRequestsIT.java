package app.platform.inspection;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestHttp.Response;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * Change requests on a published template (FA5, ADR-0191): a reader asks for a change and points at a row, a column, a
 * cell or the whole template of the version they read; the owner and the approvers see the request and nobody else
 * does; the owner accepts, declines with a reason, or turns requests into the next version; comments find their places
 * by identifier in a later version; a rejected version keeps its comments; and a request changes nothing of the
 * template. The limits are set small here so that they can be reached.
 */
@PlatformIntegrationTest
@TestPropertySource(properties = {
    "platform.outbox.enabled=true",
    "platform.workflow.enabled=false",
    "platform.inspection.change-requests.max-comments-per-request=5",
    "platform.inspection.change-requests.max-first-comments=4",
    "platform.inspection.change-requests.max-open-per-person=3",
    "platform.inspection.change-requests.max-per-template=6"})
class ChangeRequestsIT extends ChangeRequestSupport {

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
        assertThat(JsonPath.<Boolean>read(made.body(), "$.data.canAccept")).isFalse();
        assertThat(statuses(world.author().browser(), world.template())).as("no new version").containsExactly("ACTIVE");
        assertThat(JsonPath.<String>read(version(world.reader().browser(), world.template(), 1).body(),
                "$.data.version.gridHash")).as("the version read is as it was").isEqualTo(hash);
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
    void aPlaceThatIsNotInTheVersionReadAndAVersionNobodyMayReadAreRefusedWithoutRepeatingThem() {
        World world = world();
        int draft = newVersion(world.author().browser(), world.template());

        Response nowhere = ask(world.reader().browser(), world.template(), "A title", 1,
                comment("zzzzzzzz", null, "Where?"));
        Response nonsense = ask(world.reader().browser(), world.template(), "A title", 99);
        Response aDraft = ask(world.reader().browser(), world.template(), "A title", draft);

        assertThat(nowhere.status()).isEqualTo(400);
        assertThat(JsonPath.<List<String>>read(nowhere.body(), "$.error.fields['comments[0].place']")).hasSize(1);
        assertThat(nowhere.body()).doesNotContain("zzzzzzzz");
        assertThat(nonsense.status()).isEqualTo(400);
        assertThat(aDraft.status()).as("a draft answers like a version that does not exist").isEqualTo(400);
        assertThat(JsonPath.<List<String>>read(aDraft.body(), "$.error.fields.versionNo"))
                .isEqualTo(JsonPath.<List<String>>read(nonsense.body(), "$.error.fields.versionNo"));
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
    void theLimitsHold() {
        World world = world();
        String first = asked(world.reader().browser(), world.template(), "One", 1);
        asked(world.reader().browser(), world.template(), "Two", 1);
        asked(world.reader().browser(), world.template(), "Three", 1);

        Response fourth = ask(world.reader().browser(), world.template(), "Four", 1);
        assertThat(fourth.status()).as("three open requests is the most a person may have").isEqualTo(409);
        assertThat(message(fourth)).contains("3 open requests");

        // The colleague has their own allowance; the template as a whole holds six.
        asked(world.colleague().browser(), world.template(), "Colleague one", 1);
        asked(world.colleague().browser(), world.template(), "Colleague two", 1);
        asked(world.colleague().browser(), world.template(), "Colleague three", 1);
        Response seventh = ask(world.approver().browser(), world.template(), "Seven", 1);
        assertThat(seventh.status()).as(seventh.body()).isEqualTo(409);
        assertThat(message(seventh)).contains("most change requests");

        // Five comments is the most one request holds; it has four of the five places so far.
        for (int i = 0; i < 5; i++) {
            assertThat(add(world.reader().browser(), world.template(), first, comment(null, null, "Note " + i))
                    .status()).isEqualTo(200);
        }
        Response sixth = add(world.reader().browser(), world.template(), first, comment(null, null, "Note 6"));
        assertThat(sixth.status()).isEqualTo(409);
        assertThat(message(sixth)).contains("most comments");
        Response tooMany = ask(world.approver().browser(), world.template(), "Many", 1, comment(null, null, "1"),
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
        assertThat(answerRequest(world.colleague().browser(), world.template(), request, "ACCEPT", null).status())
                .isEqualTo(404);
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

        assertThat(answerRequest(world.author().browser(), world.template(), request, "DECLINE", "Not now").status())
                .isEqualTo(200);
        Response closed = add(world.reader().browser(), world.template(), request, comment(null, null, "Please?"));
        assertThat(closed.status()).isEqualTo(409);
    }

    @Test
    void theOwnerAcceptsAndDeclinesWithAReasonAndARequesterCannotAnswer() {
        World world = world();
        String open = asked(world.reader().browser(), world.template(), "Open", 1);
        String doomed = asked(world.reader().browser(), world.template(), "Doomed", 1);

        assertThat(answerRequest(world.reader().browser(), world.template(), open, "ACCEPT", null).status())
                .as("a requester may not answer their own request").isEqualTo(403);
        Response accepted = answerRequest(world.author().browser(), world.template(), open, "ACCEPT", null);
        Response noReason = answerRequest(world.author().browser(), world.template(), doomed, "DECLINE", " ");
        Response declined = answerRequest(world.approver().browser(), world.template(), doomed, "DECLINE",
                "Not our decision");

        assertThat(JsonPath.<String>read(accepted.body(), "$.data.status")).isEqualTo("ACCEPTED");
        assertThat(noReason.status()).isEqualTo(400);
        assertThat(JsonPath.<String>read(declined.body(), "$.data.status")).isEqualTo("DECLINED");
        assertThat(JsonPath.<String>read(one(world.reader().browser(), world.template(), doomed).body(),
                "$.data.decisionNote")).as("the requester is told why").isEqualTo("Not our decision");
        assertThat(answerRequest(world.author().browser(), world.template(), doomed, "ACCEPT", null).status())
                .as("a declined request stays declined").isEqualTo(409);
        assertThat(answerRequest(world.author().browser(), world.template(), open, "ACCEPT", null).status())
                .as("accepted twice").isEqualTo(409);
        assertThat(list(world.author().browser(), world.template()).body()).contains("\"openCount\":0");
    }

    // ---- turning requests into a version ----

    @Test
    void requestsAreTurnedIntoADraftThatIsACopyOfTheVersionInUseAndTheyBecomeDone() {
        World world = world();
        String first = asked(world.reader().browser(), world.template(), "First", 1, comment("r1", null, "Row"));
        String second = asked(world.colleague().browser(), world.template(), "Second", 1);
        answerRequest(world.author().browser(), world.template(), first, "ACCEPT", null);

        Response turned = turn(world.author().browser(), world.template(), first, second);

        assertThat(turned.status()).as(turned.body()).isEqualTo(200);
        assertThat(JsonPath.<Integer>read(turned.body(), "$.data.versionNo")).isEqualTo(2);
        assertThat(JsonPath.<Boolean>read(turned.body(), "$.data.created")).isTrue();
        assertThat(JsonPath.<Integer>read(turned.body(), "$.data.requests")).isEqualTo(2);
        assertThat(statuses(world.author().browser(), world.template())).containsExactly("DRAFT", "ACTIVE");
        Response linked = one(world.reader().browser(), world.template(), first);
        assertThat(JsonPath.<String>read(linked.body(), "$.data.status")).isEqualTo("DONE");
        assertThat(JsonPath.<Integer>read(linked.body(), "$.data.resultingVersion")).isEqualTo(2);
        assertThat(JsonPath.<String>read(linked.body(), "$.data.resultingVersionStatus")).isEqualTo("DRAFT");
        assertThat(gridOf(version(world.author().browser(), world.template(), 2)))
                .as("a copy of the version in use").isEqualTo(gridOf(version(world.author().browser(),
                        world.template(), 1)));
        assertThat(turn(world.author().browser(), world.template(), first).status())
                .as("a request already turned").isEqualTo(409);
        assertThat(audited(world.setting().organization(), "inspection.changerequest.turned")).isEqualTo(1);
    }

    @Test
    void aReaderCannotTurnRequestsIntoAVersionAndTheDraftIsNotSeenByReaders() {
        World world = world();
        String request = asked(world.reader().browser(), world.template(), "First", 1);

        assertThat(turn(world.reader().browser(), world.template(), request).status()).isEqualTo(403);
        assertThat(turn(world.colleague().browser(), world.template(), request).status()).isEqualTo(403);
        assertThat(turn(world.author().browser(), world.template(), request).status()).isEqualTo(200);
        assertThat(version(world.reader().browser(), world.template(), 2).status())
                .as("a draft is not read by those who may only read").isEqualTo(404);
    }

    @Test
    void aDraftThatExistsIsJoinedNotCopiedAgain() {
        World world = world();
        int draft = newVersion(world.author().browser(), world.template());
        String request = asked(world.reader().browser(), world.template(), "First", 1);

        Response turned = turn(world.author().browser(), world.template(), request);

        assertThat(turned.status()).as(turned.body()).isEqualTo(200);
        assertThat(JsonPath.<Integer>read(turned.body(), "$.data.versionNo")).isEqualTo(draft);
        assertThat(JsonPath.<Boolean>read(turned.body(), "$.data.created")).isFalse();
        assertThat(statuses(world.author().browser(), world.template())).containsExactly("DRAFT", "ACTIVE");
    }

    // ---- what happens to the comments ----

    @Test
    void commentsFindTheirPlacesByIdentifierInALaterVersionAndSayWhenAPlaceIsGone() {
        World world = world();
        String request = asked(world.reader().browser(), world.template(), "Two places", 1,
                comment("r1", null, "The row"), comment(null, world.column(), "The column"));
        assertThat(turn(world.author().browser(), world.template(), request).status()).isEqualTo(200);
        String review = editAndSend(world, 2);
        assertThat(approve(world.approver().browser(), review).status()).isEqualTo(200);
        assertThat(publish(world.approver().browser(), world.template(), 2, "PLANT1/PRD").status()).isEqualTo(200);

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
    void aRequestOnAnOlderVersionStaysAskableAboutThatVersionAfterANewOneIsPublished() {
        World world = world();
        asked(world.reader().browser(), world.template(), "Before", 1);
        int second = newVersion(world.author().browser(), world.template());
        assertThat(approve(world.approver().browser(), editAndSend(world, second)).status()).isEqualTo(200);
        assertThat(publish(world.approver().browser(), world.template(), second, "PLANT1/PRD").status())
                .isEqualTo(200);

        Response late = ask(world.reader().browser(), world.template(), "After", 1, comment(null, world.column(), "x"));

        assertThat(late.status()).as("the version was in use, so it may still be asked about").isEqualTo(201);
        assertThat(listedRequests(world.reader().browser(), world.template())).hasSize(2);
    }

    @Test
    void aRejectedVersionKeepsItsCommentsAndTheRequestCanBeReopened() {
        World world = world();
        String request = asked(world.reader().browser(), world.template(), "Keep me", 1,
                comment("r1", null, "The row"));
        assertThat(turn(world.author().browser(), world.template(), request).status()).isEqualTo(200);
        assertThat(one(world.author().browser(), world.template(), request).body()).contains("\"canReopen\":false");
        assertThat(answerRequest(world.author().browser(), world.template(), request, "REOPEN", null).status())
                .as("the version is only a draft").isEqualTo(409);
        assertThat(reject(world.approver().browser(), editAndSend(world, 2)).status()).isEqualTo(200);

        Response kept = one(world.author().browser(), world.template(), request);

        assertThat(JsonPath.<String>read(kept.body(), "$.data.resultingVersionStatus")).isEqualTo("REJECTED");
        assertThat(JsonPath.<List<String>>read(kept.body(), "$.data.comments[*].text")).containsExactly("The row");
        assertThat(JsonPath.<Boolean>read(kept.body(), "$.data.canReopen")).isTrue();
        Response reopened = answerRequest(world.author().browser(), world.template(), request, "REOPEN", null);
        assertThat(reopened.status()).as(reopened.body()).isEqualTo(200);
        assertThat(JsonPath.<String>read(reopened.body(), "$.data.status")).isEqualTo("OPEN");
        assertThat(reopened.body()).as("a request that is open names no version").doesNotContain("resultingVersion");
        assertThat(JsonPath.<List<String>>read(reopened.body(), "$.data.comments[*].text")).containsExactly("The row");
    }

    @Test
    void aRetiredTemplateTakesNoRequestsAndHasNothingToTurnInto() {
        World world = world();
        String request = asked(world.reader().browser(), world.template(), "Before", 1);
        assertThat(retire(world.approver().browser(), world.template()).status()).isEqualTo(200);

        Response late = ask(world.reader().browser(), world.template(), "After", 1);
        Response turned = turn(world.author().browser(), world.template(), request);

        assertThat(late.status()).isEqualTo(409);
        assertThat(turned.status()).isEqualTo(409);
        assertThat(list(world.reader().browser(), world.template()).body()).contains("\"canRequest\":false");
        assertThat(one(world.reader().browser(), world.template(), request).status()).as("still readable")
                .isEqualTo(200);
    }

    // ---- being told ----

    @Test
    void theOwnerIsToldWhenARequestArrivesAndTheRequesterWhenItIsAnsweredAndNobodyIsToldOfTheirOwnAct() {
        World world = world();

        String request = asked(world.reader().browser(), world.template(), "News", 1);
        add(world.reader().browser(), world.template(), request, comment(null, null, "More"));
        answerRequest(world.author().browser(), world.template(), request, "ACCEPT", null);
        answerRequest(world.author().browser(), world.template(), request, "DECLINE", "Not now");

        List<String> ownerSaw = JsonPath.read(world.author().browser().get("/api/v1/notifications").body(),
                "$.data[?(@.kind=='CHANGE_REQUEST')].body");
        List<String> requesterSaw = JsonPath.read(world.reader().browser().get("/api/v1/notifications").body(),
                "$.data[?(@.kind=='CHANGE_REQUEST')].body");
        assertThat(ownerSaw).hasSize(2);
        assertThat(ownerSaw.toString()).contains("a change was asked for").contains("a comment was added");
        assertThat(requesterSaw).hasSize(2);
        assertThat(requesterSaw.toString()).contains("it was accepted").contains("it was declined");
        String notices = world.author().browser().get("/api/v1/notifications").body();
        assertThat(JsonPath.<List<String>>read(notices, "$.data[?(@.kind=='CHANGE_REQUEST')].href"))
                .allMatch(href -> href.equals("/apps/inspection/s-templates?open=" + world.template()));
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
