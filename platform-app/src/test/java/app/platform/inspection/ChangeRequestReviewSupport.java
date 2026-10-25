package app.platform.inspection;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import com.jayway.jsonpath.JsonPath;

/**
 * What the integration tests of the review of change requests share (FA6E, ADR-0199; FA8, ADR-0202): a published
 * template
 * with its people, a second approval process on the shipped object that gives a request to the approval engine (Plant
 * QMS,
 * then Corporate QMS, which asks for the audience, then Plant QMS again, whose confirmation publishes), and short ways
 * to
 * read the review record and the approval behind a request. The words of the solution are in these tests because they
 * test
 * the solution that ships.
 */
public abstract class ChangeRequestReviewSupport extends ChangeRequestSupport {

    protected static final String CHANGE_REQUESTS = "/api/v1/change-requests";

    /**
     * A template and the review process for its change requests.
     *
     * @param world the template, its author, an approver and the people who ask
     * @param plant the person of the first and the last step
     * @param corporate the person of the middle step, or the person who is not in that step's group
     * @param plantGroup the group of the first and the last step
     * @param corporateGroup the group of the middle step
     * @param process the API name of the review process
     */
    protected record Review(World world, Person plant, Person corporate, String plantGroup, String corporateGroup,
            String process) {
    }

    /**
     * A published template and a review process of three steps: the group of Plant QMS, then the group of Corporate QMS
     * (which asks the approver for the audience), then the group of Plant QMS again, which publishes.
     *
     * @param corporateHasPeople whether the middle group has a member
     * @param corporateSkippable whether the middle step is skipped when its group has nobody
     */
    protected Review review(boolean corporateHasPeople, boolean corporateSkippable) {
        return review(corporateHasPeople, corporateSkippable, true);
    }

    /**
     * As above; {@code plantPublishes} false makes the person of the first and the last step an approver who holds no
     * Publisher policy, to see that the last approval is refused without it.
     */
    protected Review review(boolean corporateHasPeople, boolean corporateSkippable, boolean plantPublishes) {
        World world = world(false);
        Person plant = plantPublishes ? approver(world.setting()) : plainApprover(world.setting());
        Person corporate = approver(world.setting());
        String plantGroup = group(world.setting().admin(), "plant-" + System.nanoTime(), plant);
        String corporateGroup = corporateHasPeople
                ? group(world.setting().admin(), "corporate-" + System.nanoTime(), corporate)
                : group(world.setting().admin(), "corporate-" + System.nanoTime());
        String middle = corporateSkippable
                ? "{\"name\":\"Corporate\",\"label\":\"Corporate\",\"completion\":\"ANY\",\"approvers\":["
                        + groupApprover(corporateGroup) + "],\"skipWhenNobody\":true,\"asksForChoice\":true}"
                : askingStep("Corporate", groupApprover(corporateGroup));
        String process = reviewProcess(world.setting().admin(), "Review" + System.nanoTime(),
                step("Plant", groupApprover(plantGroup)), middle, step("Publish", groupApprover(plantGroup)));
        return new Review(world, plant, corporate, plantGroup, corporateGroup, process);
    }

    protected Review review() {
        return review(true, true);
    }

    /** Raises a request that must be accepted and returns the whole answer. */
    protected static Response raised(TestBrowser browser, String template, String change, String... comments) {
        Response made = ask(browser, template, change, 1, comments);
        assertThat(made.status()).as(made.body()).isEqualTo(201);
        return made;
    }

    /** What the review record says about the request's status (the copy that follows it). */
    protected static String statusCopy(TestBrowser browser, String reviewId) {
        Response record = browser.get(REVIEW_RECORDS + "/" + reviewId);
        assertThat(record.status()).as(record.body()).isEqualTo(200);
        return JsonPath.read(record.body(), "$.data.fields.status");
    }

    protected static Response queryRequests(TestBrowser browser, String body) {
        return browser.postJson(CHANGE_REQUESTS + "/query", body);
    }

    protected static Response requestById(TestBrowser browser, String request) {
        return browser.get(CHANGE_REQUESTS + "/" + request);
    }

    /**
     * Takes a request through the three steps of {@link #review}: the plant person approves the first, the corporate
     * person
     * approves the second with a location and a department, the plant person makes the copy and confirms the last with
     * the
     * window; returns the answer of the last approval.
     */
    protected static Response throughAllSteps(Review review, String template, String request, String reviewId) {
        TestBrowser plant = review.plant().browser();
        assertThat(approve(plant, approvalOf(plant, reviewId)).status()).isEqualTo(200);
        TestBrowser corporate = review.corporate().browser();
        assertThat(approveWith(corporate, approvalOf(corporate, reviewId), choices("location=PLANT1",
                "department=PLANT1/PRD")).status()).isEqualTo(200);
        copied(plant, template, request);
        return approveWith(plant, approvalOf(plant, reviewId), PUBLISH_CHOICE);
    }
}
