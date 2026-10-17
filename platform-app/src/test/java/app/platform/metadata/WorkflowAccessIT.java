package app.platform.metadata;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.identity.PlatformRole;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations.Organization;
import app.platform.testsupport.TestPlatform;
import app.platform.testsupport.TestUsers;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Who may do what with workflows and their executions (Sprint 22, ADR-0136, ADR-0137; Sprint 23, ADR-0141, ADR-0143):
 * the usual tests of every endpoint (allowed, denied, cross-tenant, forged tenant header, platform administrator
 * refused, unauthenticated).
 */
@PlatformIntegrationTest
class WorkflowAccessIT extends WorkflowTestSupport {

    /** One call to an endpoint. */
    private record Call(String method, String path, String body) {
    }

    private static final String EXECUTIONS = "/api/v1/workflows/executions";
    private static final String BODY = workflowRequest("Fresh", trigger("RECORD_CREATED"), null, true,
            announce("Ask", "a.b"));
    private static final String UPDATE = workflowUpdate("Watch", trigger("RECORD_CREATED"), null, true, 0,
            announce("Ask", "a.b"));

    private static final List<Call> EVERY_ENDPOINT = List.of(
            new Call("GET", WORKFLOWS, null),
            new Call("GET", ORDER_WORKFLOWS + "/Watch__c", null),
            new Call("POST", ORDER_WORKFLOWS, BODY),
            new Call("PUT", ORDER_WORKFLOWS + "/Watch__c", UPDATE),
            new Call("POST", ORDER_WORKFLOWS + "/Watch__c/activation", "{\"active\":false,\"version\":0}"),
            new Call("DELETE", ORDER_WORKFLOWS + "/Watch__c", null),
            new Call("POST", ORDER_WORKFLOWS + "/check", "{\"condition\":null,\"values\":{}}"),
            new Call("GET", EXECUTIONS, null),
            new Call("GET", EXECUTIONS + "/" + UUID.randomUUID(), null),
            // Sprint 23: the catalogue, the dead letters, replay, cancel and the events the platform gave up on.
            new Call("GET", WORKFLOWS + "/action-types", null),
            new Call("GET", EXECUTIONS + "/dead-letters/summary", null),
            new Call("POST", EXECUTIONS + "/" + UUID.randomUUID() + "/replay", "{}"),
            new Call("POST", EXECUTIONS + "/replay", "{\"executions\":[\"" + UUID.randomUUID() + "\"]}"),
            new Call("POST", EXECUTIONS + "/" + UUID.randomUUID() + "/cancel", null),
            new Call("GET", "/api/v1/workflows/parked-events", null),
            new Call("POST", "/api/v1/workflows/parked-events/" + UUID.randomUUID() + "/redrive", null));

    private TestBrowser adminWithWatch(Organization organization) {
        TestBrowser admin = adminWithOrder(organization);
        workflowExists(admin, "Watch", trigger("RECORD_CREATED"), null, announce("Ask", "a.b"));
        return admin;
    }

    // ---- allowed and denied ----

    @Test
    void anAdministratorMayDoEverything() {
        Organization organization = organization();
        TestBrowser admin = adminWithWatch(organization);
        String execution = anExecutionOf(organization, "Watch__c");

        Response created = admin.postJson(ORDER_WORKFLOWS, BODY);
        Response checked = admin.postJson(ORDER_WORKFLOWS + "/check", "{\"condition\":null,\"values\":{}}");
        Response history = admin.get(EXECUTIONS);
        Response one = admin.get(EXECUTIONS + "/" + execution);

        assertThat(created.status()).as(created.body()).isEqualTo(201);
        assertThat(checked.status()).as(checked.body()).isEqualTo(200);
        assertThat(history.status()).as(history.body()).isEqualTo(200);
        assertThat(JsonPath.<List<String>>read(history.body(), "$.data[*].id")).containsExactly(execution);
        assertThat(one.status()).as(one.body()).isEqualTo(200);
    }

    @Test
    void aMemberWithoutTheAbilitiesIsRefusedOnEveryEndpoint() {
        Organization organization = organization();
        TestBrowser admin = adminWithWatch(organization);
        TestBrowser person = as(organization, memberWith(organization, admin));

        for (Call call : EVERY_ENDPOINT) {
            Response refused = person.request(call.method(), call.path(), call.body());
            assertThat(refused.status()).as(call + " " + refused.body()).isEqualTo(403);
            assertThat(JsonPath.<String>read(refused.body(), "$.error.code")).isEqualTo("FORBIDDEN");
        }
    }

    @Test
    void aViewerReadsTheDefinitionsAndTheHistoryAndNothingElse() {
        Organization organization = organization();
        TestBrowser admin = adminWithWatch(organization);
        TestBrowser viewer = as(organization, memberWith(organization, admin, "workflows.view"));

        for (Call call : EVERY_ENDPOINT) {
            Response response = viewer.request(call.method(), call.path(), call.body());
            if (call.method().equals("GET")) {
                assertThat(response.status()).as(call + " " + response.body()).isIn(200, 404);
            } else {
                assertThat(response.status()).as(call + " " + response.body()).isEqualTo(403);
            }
        }
    }

    @Test
    void theAbilityToManageAloneReadsNothingAndTheAbilityToViewAloneManagesNothing() {
        Organization organization = organization();
        TestBrowser admin = adminWithWatch(organization);
        TestBrowser manager = as(organization, memberWith(organization, admin, "workflows.manage"));

        assertThat(manager.get(WORKFLOWS).status()).isEqualTo(403);
        assertThat(manager.get(EXECUTIONS).status()).isEqualTo(403);
        assertThat(manager.postJson(ORDER_WORKFLOWS + "/check", "{\"condition\":null,\"values\":{}}").status())
                .isEqualTo(200);
    }

    @Test
    void aRefusedMemberIsRecordedWithTheMissingAbility() {
        Organization organization = organization();
        TestBrowser admin = adminWithWatch(organization);
        TestBrowser person = as(organization, memberWith(organization, admin, "workflows.view"));

        person.postJson(ORDER_WORKFLOWS, BODY);

        assertThat(admin.get("/api/v1/audit-events?kind=membership.action.refused").body())
                .contains("metadata.workflow.create");
    }

    // ---- cross-tenant, forged header ----

    @Test
    void anotherOrganizationNeverSeesOrChangesTheWorkflowsOrTheHistoryOfThisOne() {
        Organization mine = organization();
        Organization theirs = organization();
        TestBrowser admin = adminWithWatch(mine);
        TestBrowser other = adminWithOrder(theirs);
        String execution = anExecutionOf(mine, "Watch__c");

        assertThat(other.get(ORDER_WORKFLOWS + "/Watch__c").status()).isEqualTo(404);
        assertThat(other.request("PUT", ORDER_WORKFLOWS + "/Watch__c", UPDATE).status()).isEqualTo(404);
        assertThat(other.postJson(ORDER_WORKFLOWS + "/Watch__c/activation", "{\"active\":false,\"version\":0}")
                .status()).isEqualTo(404);
        assertThat(other.request("DELETE", ORDER_WORKFLOWS + "/Watch__c", null).status()).isEqualTo(404);
        assertThat(other.get(EXECUTIONS + "/" + execution).status()).isEqualTo(404);
        assertThat(JsonPath.<List<String>>read(other.get(WORKFLOWS).body(), "$.data[*].apiName")).isEmpty();
        assertThat(JsonPath.<List<String>>read(other.get(EXECUTIONS).body(), "$.data[*].id")).isEmpty();
        assertThat(workflowNames(admin)).containsExactly("Watch__c");
        assertThat(JsonPath.<List<String>>read(admin.get(EXECUTIONS).body(), "$.data[*].id"))
                .containsExactly(execution);
    }

    @Test
    void aForgedTenantHeaderChangesNothing() {
        Organization mine = organization();
        Organization theirs = organization();
        TestBrowser admin = adminWithWatch(mine);
        TestBrowser other = adminWithOrder(theirs);
        workflowExists(other, "Secret", trigger("RECORD_CREATED"), null, announce("Ask", "a.b"));
        String secretExecution = anExecutionOf(theirs, "Secret__c");
        String[] forged = {"X-Tenant-Id", theirs.id().value().toString(), "X-Forwarded-Host", theirs.host(),
            "X-Organization", theirs.tenant().slug()};

        Response plain = admin.get(WORKFLOWS);
        Response withHeaders = admin.get(WORKFLOWS, forged);
        Response history = admin.get(EXECUTIONS, forged);
        Response secret = admin.get(EXECUTIONS + "/" + secretExecution, forged);
        Response create = admin.request("POST", ORDER_WORKFLOWS, BODY, forged);

        assertThat(withHeaders.body()).isEqualTo(plain.body()).doesNotContain("Secret");
        assertThat(history.body()).doesNotContain(secretExecution);
        assertThat(secret.status()).isEqualTo(404);
        assertThat(create.status()).as(create.body()).isEqualTo(201);
        assertThat(workflowNames(other)).containsExactly("Secret__c");
        assertThat(workflowNames(admin)).contains("Fresh__c");
    }

    // ---- platform administrator, unauthenticated ----

    @Test
    void aPlatformAdministratorIsRefusedOnEveryEndpoint() {
        Organization organization = organization();
        adminWithWatch(organization);
        TestUsers.TestUser person = TestPlatform.person(users, PlatformRole.PLATFORM_ADMIN);
        TestBrowser platformAdmin = TestPlatform.signedIn(port, person);

        for (Call call : EVERY_ENDPOINT) {
            Response refused = platformAdmin.request(call.method(), call.path(), call.body());
            assertThat(refused.status()).as(call + " " + refused.body()).isEqualTo(404);
        }
    }

    @Test
    void anUnauthenticatedCallerIsRefusedOnEveryEndpoint() {
        Organization organization = organization();
        adminWithWatch(organization);
        TestBrowser nobody = new TestBrowser(port, organization.host());

        for (Call call : EVERY_ENDPOINT) {
            assertThat(nobody.request(call.method(), call.path(), call.body()).status()).as(call.toString())
                    .isEqualTo(401);
        }
    }
}
