package app.platform.inspection;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import com.jayway.jsonpath.JsonPath;

/**
 * What the integration tests of the review of change requests share (FA6E, ADR-0199): a published template with its
 * people, a second approval process on the shipped object that gives a request to the approval engine (Plant QMS, then
 * Corporate QMS), and short ways to read the review record and the approval behind a request. The words of the solution
 * are in these tests because they test the solution that ships.
 */
public abstract class ChangeRequestReviewSupport extends ChangeRequestSupport {

            protected static final String CHANGE_REQUESTS = "/api/v1/change-requests";

    /**
     * A template and the review process for its change requests.
     *
     * @param world the template, its author, an approver and the people who ask
     * @param plant the person of the first step
     * @param corporate the person of the second step, or the person who is not in the second step's group
     * @param plantGroup the group of the first step
     * @param corporateGroup the group of the second step
     * @param process the API name of the review process
     */
    protected record Review(World world, Person plant, Person corporate, String plantGroup, String corporateGroup,
            String process) {
    }

    /**
     * A published template and a review process of two steps: the group of Plant QMS, then the group of Corporate QMS.
     *
     * @param corporateHasPeople whether the second group has a member
     * @param corporateSkippable whether the second step is skipped when its group has nobody
     */
    protected Review review(boolean corporateHasPeople, boolean corporateSkippable) {
        World world = world(false);
        Person plant = approver(world.setting());
        Person corporate = approver(world.setting());
        String plantGroup = group(world.setting().admin(), "plant-" + System.nanoTime(), plant);
        String corporateGroup = corporateHasPeople
                ? group(world.setting().admin(), "corporate-" + System.nanoTime(), corporate)
                : group(world.setting().admin(), "corporate-" + System.nanoTime());
        String second = corporateSkippable
                ? skippableStep("Corporate", groupApprover(corporateGroup))
                : step("Corporate", groupApprover(corporateGroup));
        String process = reviewProcess(world.setting().admin(), "Review" + System.nanoTime(),
                step("Plant", groupApprover(plantGroup)), second);
        return new Review(world, plant, corporate, plantGroup, corporateGroup, process);
    }

    protected Review review() {
        return review(true, true);
    }

    /** Raises a request that must be accepted and returns the whole answer. */
    protected static Response raised(TestBrowser browser, String template, String title, String... comments) {
        Response made = ask(browser, template, title, 1, comments);
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
}
