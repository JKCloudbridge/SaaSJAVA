package app.platform.inspection;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * Who may publish an approved version (FA6E, ADR-0199): the people who hold the shipped Publisher policy, directly or
 * through a group, and the Organization administrator, who holds every ability. Approving gives no right to publish,
 * retire or take a template back. The server decides on every act, whatever the button said, and taking the policy away
 * takes the right away at the next question.
 */
@PlatformIntegrationTest
@TestPropertySource(properties = {"platform.outbox.enabled=true", "platform.workflow.enabled=false"})
class TemplatePublishAuthorityIT extends ChangeRequestReviewSupport {

    private static final String GROUPS = "/api/v1/groups";

    /** A template approved by a plain approver and not yet published, with the people around it. */
    private record Flow(Setting setting, Person author, Person approver, Person publisher, String template) {
    }

    private Flow approvedFlow() {
        Setting setting = setting();
        Person author = author(setting);
        Person approver = plainApprover(setting);
        Person publisher = publisher(setting);
        Person reader = reader(setting);
        String process = process(setting.admin(), "Flow" + System.nanoTime(), step("Only", user(approver.userId())));
        department(setting.admin(), "PLANT1", "PRD", group(setting.admin(), "dept-" + System.nanoTime(), reader));
        String template = templateWithSheet(author.browser(), "WI", "PRD");
        String request = submitted(author.browser(), template, process);
        assertThat(approve(approver.browser(), request).status()).isEqualTo(200);
        assertThat(statusOf(approver.browser(), request)).isEqualTo("APPROVED");
        return new Flow(setting, author, approver, publisher, template);
    }

    private static boolean canPublish(TestBrowser browser, String template) {
        Response view = lifecycle(browser, template);
        assertThat(view.status()).as(view.body()).isEqualTo(200);
        return JsonPath.read(view.body(), "$.data.canPublish");
    }

    @Test
    void anApproverWhoHoldsOnlyTheApproverPolicyCannotPublishEvenIfTheButtonIsForced() {
        Flow flow = approvedFlow();

        assertThat(canPublish(flow.approver().browser(), flow.template())).as("the button is not offered").isFalse();
        assertThat(publish(flow.approver().browser(), flow.template(), null, "PLANT1/PRD").status()).isEqualTo(403);
        assertThat(state(flow.publisher().browser(), flow.template())).as("nothing changed").isEqualTo("APPROVED");
    }

    @Test
    void aPublisherPublishesAndTheStatusBecomesActive() {
        Flow flow = approvedFlow();

        assertThat(canPublish(flow.publisher().browser(), flow.template())).isTrue();
        assertThat(publish(flow.publisher().browser(), flow.template(), null, "PLANT1/PRD").status()).isEqualTo(200);
        assertThat(state(flow.publisher().browser(), flow.template())).isEqualTo("ACTIVE");
    }

    @Test
    void anApproverWhoCannotPublishCannotTakeItBackOrRetireEither() {
        Flow flow = approvedFlow();
        assertThat(publish(flow.publisher().browser(), flow.template(), null, "PLANT1/PRD").status()).isEqualTo(200);

        assertThat(unpublish(flow.approver().browser(), flow.template()).status()).isEqualTo(403);
        assertThat(retire(flow.approver().browser(), flow.template()).status()).isEqualTo(403);
        assertThat(unpublish(flow.publisher().browser(), flow.template()).status()).isEqualTo(200);
        assertThat(retire(flow.publisher().browser(), flow.template()).status()).isEqualTo(200);
    }

    @Test
    void thePublisherPolicyGivenToAGroupLetsTheGroupPublishAndTakingItBackStopsIt() {
        Flow flow = approvedFlow();
        String publisherPolicy = policyId(flow.setting().admin(), "Publisher");
        String group = group(flow.setting().admin(), "plant-" + System.nanoTime(), flow.approver());
        assertThat(publish(flow.approver().browser(), flow.template(), null, "PLANT1/PRD").status()).isEqualTo(403);

        Response given = flow.setting().admin().postJson(GROUPS + "/" + group + "/policies",
                "{\"policyId\":\"" + publisherPolicy + "\"}");
        assertThat(given.status()).as(given.body()).isIn(200, 201, 204);

        assertThat(canPublish(flow.approver().browser(), flow.template())).as("through the group").isTrue();
        assertThat(publish(flow.approver().browser(), flow.template(), null, "PLANT1/PRD").status()).isEqualTo(200);

        Response taken = flow.setting().admin().request("DELETE", GROUPS + "/" + group + "/policies/" + publisherPolicy,
                null);
        assertThat(taken.status()).as(taken.body()).isIn(200, 204);

        assertThat(canPublish(flow.approver().browser(), flow.template())).as("the next question").isFalse();
        assertThat(unpublish(flow.approver().browser(), flow.template()).status()).isEqualTo(403);
    }

    @Test
    void anAdministratorMayAlwaysPublish() {
        Flow flow = approvedFlow();

        assertThat(canPublish(flow.setting().admin(), flow.template())).isTrue();
        assertThat(publish(flow.setting().admin(), flow.template(), null, "PLANT1/PRD").status()).isEqualTo(200);
    }

    @Test
    void thePublisherPolicyIsTheOnlyShippedPolicyThatCarriesThePublishAndRetireAbilities() {
        Setting setting = setting();
        applicationsOf(setting.admin());
        Response list = setting.admin().get(POLICIES);

        List<String> withPublish = JsonPath.read(list.body(), "$.data[?(@.abilities contains 'sheets.publish')].name");
        List<String> withRetire = JsonPath.read(list.body(), "$.data[?(@.abilities contains 'sheets.retire')].name");

        assertThat(withPublish).containsExactly("Publisher");
        assertThat(withRetire).containsExactly("Publisher");
    }
}
