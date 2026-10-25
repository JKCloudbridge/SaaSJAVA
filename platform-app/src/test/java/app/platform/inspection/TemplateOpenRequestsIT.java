package app.platform.inspection;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * The asterisk of a template (FA6E, ADR-0199): the number of change requests that still wait for someone (open, or
 * accepted and not yet made into a version) is on the master list and on the lifecycle of a template, counted as far as
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
    void anAcceptedRequestStillCountsUntilItIsTurnedIntoAVersionAndADeclinedOneDoesNot() {
        World world = world();
        String accepted = asked(world.reader().browser(), world.template(), "Accepted", 1);
        String declined = asked(world.reader().browser(), world.template(), "Declined", 1);

        assertThat(onList(world.author().browser(), world.template())).isEqualTo(2);
        assertThat(answerRequest(world.author().browser(), world.template(), accepted, "ACCEPT", null).status())
                .isEqualTo(200);
        assertThat(answerRequest(world.author().browser(), world.template(), declined, "DECLINE", "No").status())
                .isEqualTo(200);
        assertThat(onList(world.author().browser(), world.template())).as("accepted waits for its version")
                .isEqualTo(1);
        assertThat(onLifecycle(world.author().browser(), world.template())).isEqualTo(1);

        assertThat(turn(world.author().browser(), world.template(), accepted).status()).isEqualTo(200);

        assertThat(onList(world.author().browser(), world.template())).as("done does not wait").isEqualTo(0);
        assertThat(onLifecycle(world.author().browser(), world.template())).isEqualTo(0);
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
