package app.platform.inspection;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * The asterisk of a template (FA6E, ADR-0199): the number of change requests that still wait for someone (there is
 * at most one on a template: it is open until it is published or declined) is on the master list and on the lifecycle
 * of a template, counted as far as
 * the person asking may see the requests. It is a reading aid; it decides nothing.
 */
@PlatformIntegrationTest
@TestPropertySource(properties = {"platform.outbox.enabled=true", "platform.workflow.enabled=false"})
class TemplateOpenRequestsIT extends ChangeRequestReviewSupport {

    private static int onList(TestBrowser browser, String template) {
        List<Integer> counts = JsonPath.read(browser.get(TEMPLATES + "?size=200").body(),
                "$.data.rows[?(@.id=='" + template + "')].openChangeRequests");
        return counts.isEmpty() ? -1 : counts.get(0);
    }

    private static int onLifecycle(TestBrowser browser, String template) {
        return JsonPath.<Integer>read(lifecycle(browser, template).body(), "$.data.openChangeRequests");
    }

    @Test
    void aRequestThatWaitsIsCountedForThosewhoSeeItAndNotForThoseWhoDoNot() {
        World world = world();
        asked(world.reader().browser(), world.template(), "Mine", 1);

        assertThat(onList(world.author().browser(), world.template())).as("the owner").isEqualTo(1);
        assertThat(onList(world.approver().browser(), world.template())).as("the approver").isEqualTo(1);
        assertThat(onList(world.reader().browser(), world.template())).as("the person who asked").isEqualTo(1);
        assertThat(onList(world.colleague().browser(), world.template())).as("a colleague sees none of it")
                .isEqualTo(0);
        assertThat(onLifecycle(world.author().browser(), world.template())).isEqualTo(1);
        assertThat(onLifecycle(world.colleague().browser(), world.template())).isEqualTo(0);
    }

    @Test
    void aDeclinedRequestAndAPublishedOneNoLongerCountAndTheNextOneDoes() {
        World world = world();
        String declined = asked(world.reader().browser(), world.template(), "Declined", 1);

        assertThat(onList(world.author().browser(), world.template())).isEqualTo(1);
        assertThat(decline(world, declined, "No").status()).isEqualTo(200);
        assertThat(onList(world.author().browser(), world.template())).as("declined does not wait").isZero();
        assertThat(onLifecycle(world.author().browser(), world.template())).isZero();

        String published = asked(world.reader().browser(), world.template(), "Published", 1);
        assertThat(onLifecycle(world.author().browser(), world.template())).isEqualTo(1);
        copied(world.approver().browser(), world.template(), published);
        assertThat(onLifecycle(world.author().browser(), world.template())).as("a copy is still a request in progress")
                .isEqualTo(1);
        assertThat(approveWith(world.approver().browser(), approvalOfRequest(world, published), PUBLISH_CHOICE)
                .status()).isEqualTo(200);

        assertThat(onList(world.author().browser(), world.template())).as("done does not wait").isZero();
        assertThat(onLifecycle(world.author().browser(), world.template())).isZero();
    }

    @Test
    void aTemplateWithNoRequestsHasNoAsteriskAndOneRequestDoesNotMarkAnotherTemplate() {
        World world = world();
        String other = templateWithSheet(world.author().browser(), "WI", "PRD");
        asked(world.reader().browser(), world.template(), "Mine", 1);

        assertThat(onList(world.author().browser(), other)).isEqualTo(0);
        assertThat(onLifecycle(world.author().browser(), other)).isEqualTo(0);
    }

    @Test
    void anotherOrganizationsRequestsAreNeverCounted() {
        World mine = world();
        World theirs = world();
        asked(mine.reader().browser(), mine.template(), "Mine", 1);

        assertThat(onLifecycle(theirs.approver().browser(), theirs.template())).isEqualTo(0);
        assertThat(onList(theirs.approver().browser(), theirs.template())).isEqualTo(0);
    }
}
