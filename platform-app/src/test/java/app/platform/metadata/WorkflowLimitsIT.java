package app.platform.metadata;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * The limits of workflows (Sprint 22, ADR-0136), with small numbers: how many workflows an object may have, and how
 * many definitions one workflow may have been through. Reaching a limit is a CONFLICT that says so, and changes
 * nothing.
 */
@PlatformIntegrationTest
@TestPropertySource(properties = {
    "platform.metadata.limits.max-workflows-per-object=2",
    "platform.metadata.limits.max-workflow-versions=3"})
class WorkflowLimitsIT extends WorkflowTestSupport {

    @Test
    void anObjectHasAtMostTheWorkflowsTheLimitAllows() {
        TestBrowser admin = adminWithOrder();
        workflowExists(admin, "One", trigger("RECORD_CREATED"), null, announce("Ask", "a.b"));
        workflowExists(admin, "Two", trigger("RECORD_CREATED"), null, announce("Ask", "a.b"));

        Response third = createWorkflow(admin, "Three", trigger("RECORD_CREATED"), null, announce("Ask", "a.b"));

        assertThat(third.status()).as(third.body()).isEqualTo(409);
        assertThat(firstProblem(third)).contains("the most workflows it may have (2)");
        assertThat(workflowNames(admin)).containsExactly("One__c", "Two__c");
        admin.request("DELETE", ORDER_WORKFLOWS + "/One__c", null);
        assertThat(createWorkflow(admin, "Three", trigger("RECORD_CREATED"), null, announce("Ask", "a.b")).status())
                .as("a removed workflow gives its place back").isEqualTo(201);
    }

    @Test
    void aWorkflowThatHasBeenThroughTheMostDefinitionsItMayHaveIsToldToStartANewOne() {
        TestBrowser admin = adminWithOrder();
        workflowExists(admin, "Watch", trigger("RECORD_CREATED"), null, announce("Ask", "first.event"));
        admin.request("PUT", ORDER_WORKFLOWS + "/Watch__c", workflowUpdate("Watch", trigger("RECORD_CREATED"), null,
                true, workflowVersion(admin, "Watch__c"), announce("Ask", "second.event")));
        admin.request("PUT", ORDER_WORKFLOWS + "/Watch__c", workflowUpdate("Watch", trigger("RECORD_CREATED"), null,
                true, workflowVersion(admin, "Watch__c"), announce("Ask", "third.event")));

        Response fourth = admin.request("PUT", ORDER_WORKFLOWS + "/Watch__c", workflowUpdate("Watch",
                trigger("RECORD_CREATED"), null, true, workflowVersion(admin, "Watch__c"),
                announce("Ask", "fourth.event")));
        Response relabel = admin.request("PUT", ORDER_WORKFLOWS + "/Watch__c", workflowUpdate("Renamed",
                trigger("RECORD_CREATED"), null, true, workflowVersion(admin, "Watch__c"),
                announce("Ask", "third.event")));

        assertThat(fourth.status()).as(fourth.body()).isEqualTo(409);
        assertThat(firstProblem(fourth)).contains("Make a new workflow instead");
        assertThat(JsonPath.<String>read(admin.get(ORDER_WORKFLOWS + "/Watch__c").body(),
                "$.data.actions[0].settings.event")).isEqualTo("third.event");
        assertThat(relabel.status()).as("a change of the label needs no new definition").isEqualTo(200);
    }
}
