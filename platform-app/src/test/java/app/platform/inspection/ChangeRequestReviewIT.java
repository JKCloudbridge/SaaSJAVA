package app.platform.inspection;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestHttp.Response;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * A change request goes through an approval of its own (FA6E, ADR-0199): raising it makes a small record and sends it
 * to the process the organization set up (Plant QMS, then Corporate QMS); it is accepted only when the whole process
 * ends approved, declined for good when it ends rejected, and it can be turned into a version only after it was
 * accepted. Without such a process the request is answered directly, as before.
 */
@PlatformIntegrationTest
@TestPropertySource(properties = {"platform.outbox.enabled=true", "platform.workflow.enabled=false"})
class ChangeRequestReviewIT extends ChangeRequestReviewSupport {

    @Test
    void raisingARequestMakesItsReviewRecordAndSendsItToThePeopleOfTheFirstStep() {
        Review review = review();
        Response made = raised(review.world().reader().browser(), review.world().template(), "Walkway",
                comment(null, null, "Please say 3 metres"));
        String reviewId = reviewIdOf(made);

        assertThat(JsonPath.<String>read(made.body(), "$.data.status")).isEqualTo("OPEN");
        Response plant = approvalPanel(review.plant().browser(), reviewId);
        assertThat(plant.status()).as(plant.body()).isEqualTo(200);
        assertThat(JsonPath.<Boolean>read(plant.body(), "$.data.actions.canApprove")).as("Plant QMS holds the task")
                .isTrue();
        assertThat(JsonPath.<Boolean>read(approvalPanel(review.world().reader().browser(), reviewId).body(),
                "$.data.actions.canApprove")).as("the person who asked does not decide").isFalse();
        assertThat(statusCopy(review.world().reader().browser(), reviewId)).isEqualTo("OPEN");
        assertThat(review.world().outsider().browser().get(REVIEW_RECORDS + "/" + reviewId).status())
                .as("the review record is private").isEqualTo(404);
        assertThat(JsonPath.<Boolean>read(approvalPanel(review.corporate().browser(), reviewId).body(),
                "$.data.actions.canApprove")).as("Corporate QMS is asked only at the second step").isFalse();
    }

    @Test
    void theRequestIsAcceptedOnlyWhenBothStepsHaveApprovedAndThenItCanBeTurnedIntoAVersion() {
        Review review = review();
        String template = review.world().template();
        String request = JsonPath.read(raised(review.world().reader().browser(), template, "Walkway").body(),
                "$.data.id");
        String reviewId = reviewIdOf(one(review.world().reader().browser(), template, request));
        String approval = approvalOf(review.plant().browser(), reviewId);

        assertThat(turn(review.world().author().browser(), template, request).status())
                .as("not before it is accepted").isEqualTo(409);
        assertThat(JsonPath.<Boolean>read(approvalPanel(review.corporate().browser(), reviewId).body(),
                "$.data.actions.canApprove")).as("the second step is not asked yet").isFalse();
        assertThat(approve(review.plant().browser(), approval).status()).isEqualTo(200);
        assertThat(requestStatus(review.world().author().browser(), template, request))
                .as("one step is not the end").isEqualTo("OPEN");
        assertThat(approve(review.corporate().browser(), approval).status()).isEqualTo(200);

        assertThat(requestStatus(review.world().author().browser(), template, request)).isEqualTo("ACCEPTED");
        assertThat(statusCopy(review.world().reader().browser(), reviewId)).isEqualTo("ACCEPTED");
        assertThat(turn(review.world().author().browser(), template, request).status()).isEqualTo(200);
        assertThat(requestStatus(review.world().author().browser(), template, request)).isEqualTo("DONE");
        assertThat(statusCopy(review.world().reader().browser(), reviewId)).isEqualTo("DONE");
    }

    @Test
    void aRejectionDeclinesTheRequestForGoodAndNothingCanBeDoneWithItAfterwards() {
        Review review = review();
        String template = review.world().template();
        String request = JsonPath.read(raised(review.world().reader().browser(), template, "Walkway").body(),
                "$.data.id");
        String reviewId = reviewIdOf(one(review.world().reader().browser(), template, request));
        String approval = approvalOf(review.plant().browser(), reviewId);

        assertThat(reject(review.plant().browser(), approval).status()).isEqualTo(200);

        assertThat(requestStatus(review.world().reader().browser(), template, request)).isEqualTo("DECLINED");
        assertThat(statusCopy(review.world().reader().browser(), reviewId)).isEqualTo("DECLINED");
        assertThat(turn(review.world().author().browser(), template, request).status()).isEqualTo(409);
        assertThat(reopen(review.world().author().browser(), template, request).status())
                .as("a declined request is final: the person raises a new one").isEqualTo(409);
        assertThat(JsonPath.<java.util.List<String>>read(approvalPanel(review.world().reader().browser(), reviewId)
                .body(), "$.data.current.history[?(@.kind=='REJECTED')].comment"))
                .as("the reason stays with the approval").containsExactly("Not good enough");
    }

    @Test
    void aSecondStepWithNobodyInItIsSkippedAndThePlantStepFinishesTheRequest() {
        Review review = review(false, true);
        String template = review.world().template();
        String request = JsonPath.read(raised(review.world().reader().browser(), template, "Walkway").body(),
                "$.data.id");
        String reviewId = reviewIdOf(one(review.world().reader().browser(), template, request));

        assertThat(approve(review.plant().browser(), approvalOf(review.plant().browser(), reviewId)).status())
                .isEqualTo(200);

        assertThat(requestStatus(review.world().author().browser(), template, request)).isEqualTo("ACCEPTED");
    }

    @Test
    void aRequestIsRefusedWithTheEnginesWordsWhenNobodyCanDecideTheFirstStepAndLeavesNothingBehind() {
        World world = world();
        String empty = group(world.setting().admin(), "plant-empty-" + System.nanoTime());
        reviewProcess(world.setting().admin(), "Nobody" + System.nanoTime(), step("Plant", groupApprover(empty)));

        Response refused = ask(world.reader().browser(), world.template(), "Walkway", 1);

        assertThat(refused.status()).as(refused.body()).isEqualTo(409);
        assertThat(listedRequests(world.author().browser(), world.template())).as("no request was made").isEmpty();
        assertThat(JsonPath.<java.util.List<String>>read(world.approver().browser().get(REVIEW_RECORDS).body(),
                "$.data[*].id")).as("no review record was left").isEmpty();
    }

    @Test
    void thereIsNoWayToAcceptOrDeclineARequestExceptItsApproval() {
        Review review = review();
        String template = review.world().template();
        String request = JsonPath.read(raised(review.world().reader().browser(), template, "Walkway").body(),
                "$.data.id");

        Response owner = review.world().author().browser().postJson(changes(template) + "/" + request + "/answer",
                "{\"action\":\"ACCEPT\"}");
        Response approver = review.world().approver().browser().postJson(
                changes(template) + "/" + request + "/answer", "{\"action\":\"DECLINE\",\"note\":\"No\"}");

        assertThat(owner.status()).as("the old answer endpoint is gone").isGreaterThanOrEqualTo(400);
        assertThat(approver.status()).isGreaterThanOrEqualTo(400);
        assertThat(requestStatus(review.world().author().browser(), template, request)).isEqualTo("OPEN");
        Response view = one(review.world().author().browser(), template, request);
        assertThat(view.body()).doesNotContain("canAccept").doesNotContain("canDecline");
    }
    @Test
    void aRequestWithdrawnFromItsReviewStaysOpenAndIsNotAccepted() {
        Review review = review();
        String template = review.world().template();
        String request = JsonPath.read(raised(review.world().reader().browser(), template, "Walkway").body(),
                "$.data.id");
        String reviewId = reviewIdOf(one(review.world().reader().browser(), template, request));

        assertThat(recall(review.world().reader().browser(), approvalOf(review.plant().browser(), reviewId))
                .status()).isEqualTo(200);

        assertThat(requestStatus(review.world().reader().browser(), template, request)).isEqualTo("OPEN");
        assertThat(turn(review.world().author().browser(), template, request).status()).isEqualTo(409);
    }

    @Test
    void withoutAProcessForRequestsNoRequestCanBeRaisedAndTheWordsSayWhatToSetUp() {
        World world = world(false);

        Response refused = ask(world.reader().browser(), world.template(), "Walkway", 1);

        assertThat(refused.status()).as(refused.body()).isEqualTo(409);
        assertThat(message(refused)).contains("not set one up");
        assertThat(listedRequests(world.author().browser(), world.template())).as("nothing was made").isEmpty();
        assertThat(JsonPath.<java.util.List<String>>read(world.approver().browser().get(REVIEW_RECORDS).body(),
                "$.data[*].id")).as("no review record was left").isEmpty();
    }
    @Test
    void aRequestThatWasTurnedAndWhoseVersionWasRejectedGoesBackToAcceptedNotToOpen() {
        Review review = review();
        String template = review.world().template();
        String request = JsonPath.read(raised(review.world().reader().browser(), template, "Walkway").body(),
                "$.data.id");
        String reviewId = reviewIdOf(one(review.world().reader().browser(), template, request));
        String approval = approvalOf(review.plant().browser(), reviewId);
        assertThat(approve(review.plant().browser(), approval).status()).isEqualTo(200);
        assertThat(approve(review.corporate().browser(), approval).status()).isEqualTo(200);
        assertThat(turn(review.world().author().browser(), template, request).status()).isEqualTo(200);
        int draft = JsonPath.<Integer>read(one(review.world().author().browser(), template, request).body(),
                "$.data.resultingVersion");
        String versionApproval = editAndSend(review.world(), draft);
        assertThat(reject(review.world().approver().browser(), versionApproval).status()).isEqualTo(200);

        Response reopened = reopen(review.world().author().browser(), template, request);

        assertThat(reopened.status()).as(reopened.body()).isEqualTo(200);
        assertThat(JsonPath.<String>read(reopened.body(), "$.data.status")).isEqualTo("ACCEPTED");
        assertThat(statusCopy(review.world().reader().browser(), reviewId)).isEqualTo("ACCEPTED");
    }
}
