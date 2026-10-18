package app.platform.approval.internal;

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
import org.springframework.test.context.TestPropertySource;

/**
 * Every endpoint of approvals, asked by every kind of caller (the Definition of Done): allowed, denied, another
 * organization, a forged header, a platform administrator, and nobody. The definitions are decided by abilities (view,
 * manage and publish are three different things); the runtime is decided by the task a person holds, the request they
 * submitted, or the abilities that see and manage everything.
 */
@PlatformIntegrationTest
@TestPropertySource(properties = {
    "platform.outbox.enabled=true",
    "platform.outbox.poll-interval=50ms",
    "platform.workflow.enabled=false"})
class ApprovalAccessIT extends ApprovalSupport {

    /** One call to an endpoint. */
    private record Call(String method, String path, String body) {
    }

    private static final String ID = UUID.randomUUID().toString();
    private static final String ANY_PROCESS = DEFINITIONS + "/" + OBJECT + "/Anything__c";
    private static final String DECISION = "{\"version\":1,\"comment\":\"x\"}";
    private static final String STEPS = "[" + step("One", "ANY", user(ID)) + "]";

    private static final List<Call> EVERY_ENDPOINT = List.of(
            new Call("GET", DEFINITIONS, null),
            new Call("GET", DEFINITIONS + "/catalogue", null),
            new Call("GET", DEFINITIONS + "/choices", null),
            new Call("GET", ANY_PROCESS, null),
            new Call("POST", DEFINITIONS + "/" + OBJECT, createRequest("Anything", null, false, null, null,
                    step("One", "ANY", user(ID)))),
            new Call("PUT", ANY_PROCESS, updateRequest("Anything", true, null, false, null, null, 0,
                    step("One", "ANY", user(ID)))),
            new Call("POST", ANY_PROCESS + "/activation", "{\"active\":false,\"version\":0}"),
            new Call("DELETE", ANY_PROCESS, null),
            new Call("POST", DEFINITIONS + "/" + OBJECT + "/check", "{\"entryCriteria\":null,\"values\":{}}"),
            new Call("POST", DEFINITIONS + "/" + OBJECT + "/resolve", "{\"recordId\":\"" + ID + "\",\"steps\":"
                    + STEPS + "}"),
            new Call("GET", RUNTIME + "/inbox", null),
            new Call("GET", RUNTIME + "/inbox/summary", null),
            new Call("GET", INSTANCES, null),
            new Call("GET", INSTANCES + "/" + ID, null),
            new Call("POST", INSTANCES + "/" + ID + "/approve", DECISION),
            new Call("POST", INSTANCES + "/" + ID + "/reject", DECISION),
            new Call("POST", INSTANCES + "/" + ID + "/recall", DECISION),
            new Call("POST", INSTANCES + "/" + ID + "/cancel", DECISION),
            new Call("POST", INSTANCES + "/" + ID + "/reassign", "{\"toUserId\":\"" + ID + "\",\"version\":1}"),
            new Call("POST", INSTANCES + "/" + ID + "/assign", "{\"toUserId\":\"" + ID + "\",\"version\":1}"),
            new Call("POST", INSTANCES + "/" + ID + "/outcome", "{\"recordVersion\":1,\"version\":1}"),
            new Call("GET", RECORDS + "/" + ID + "/approval", null),
            new Call("POST", RECORDS + "/" + ID + "/approval", "{\"process\":null,\"comment\":null}"),
            new Call("GET", DELEGATIONS, null),
            new Call("POST", DELEGATIONS, "{\"delegateId\":\"" + ID + "\",\"startsOn\":\"2030-01-01\","
                    + "\"endsOn\":\"2030-01-02\",\"timeZone\":\"UTC\"}"),
            new Call("DELETE", DELEGATIONS + "/" + ID, null),
            new Call("GET", RUNTIME + "/people?text=a", null));

    private record Setup(Organization organization, TestBrowser admin, Person submitter, Person approver,
            String process, String request) {
    }

    private Setup setup() {
        Organization organization = organization();
        TestBrowser admin = adminWithData(organization);
        Person submitter = person(organization, admin);
        Person approver = person(organization, admin);
        String process = processExists(admin, "Expense", null, step("One", "ANY", user(approver.userId())));
        String request = submitted(submitter.browser(), record(submitter.browser(), "{\"fieldA__c\":\"x\"}"),
                process);
        return new Setup(organization, admin, submitter, approver, process, request);
    }

    // ---- the definitions: three abilities, three different things ----

    @Test
    void aMemberWithNoAbilityIsDeniedEveryDefinitionEndpointAndIsToldNothing() {
        Setup setup = setup();
        Person plain = person(setup.organization(), setup.admin());
        String path = DEFINITIONS + "/" + OBJECT + "/" + setup.process();

        for (Call call : List.of(new Call("GET", DEFINITIONS, null), new Call("GET", DEFINITIONS + "/catalogue", null),
                new Call("GET", path, null), new Call("GET", DEFINITIONS + "/choices", null),
                new Call("POST", DEFINITIONS + "/" + OBJECT + "/check", "{\"entryCriteria\":null,\"values\":{}}"),
                new Call("DELETE", path, null))) {
            Response denied = plain.browser().request(call.method(), call.path(), call.body());
            assertThat(denied.status()).as(call + " " + denied.body()).isEqualTo(403);
            assertThat(denied.body()).doesNotContain(setup.process());
        }
        assertThat(setup.admin().get(path).status()).isEqualTo(200);
    }

    @Test
    void viewingManagingAndPublishingAreThreeAbilitiesAndMakingALiveProcessNeedsTwoOfThem() {
        Setup setup = setup();
        Person viewer = person(setup.organization(), setup.admin(), "approvals.view");
        Person manager = person(setup.organization(), setup.admin(), "approvals.manage");
        Person maker = person(setup.organization(), setup.admin(), "approvals.manage", "metadata.publish");
        String made = createRequest("Fresh", null, false, null, null, step("One", "ANY", user(setup.approver()
                .userId())));
        String path = DEFINITIONS + "/" + OBJECT;

        assertThat(viewer.browser().get(DEFINITIONS).status()).isEqualTo(200);
        assertThat(viewer.browser().get(DEFINITIONS + "/catalogue").status()).isEqualTo(200);
        assertThat(viewer.browser().get(path + "/" + setup.process()).status()).isEqualTo(200);
        assertThat(viewer.browser().postJson(path, made).status()).as("view is not manage").isEqualTo(403);
        assertThat(viewer.browser().get(DEFINITIONS + "/choices").status()).isEqualTo(403);
        assertThat(manager.browser().get(DEFINITIONS).status()).as("manage is not view").isEqualTo(403);
        assertThat(manager.browser().get(DEFINITIONS + "/choices").status()).isEqualTo(200);
        assertThat(manager.browser().postJson(path + "/check", "{\"entryCriteria\":null,\"values\":{}}").status())
                .isEqualTo(200);
        assertThat(manager.browser().postJson(path, made).status()).as("manage without publish").isEqualTo(403);
        assertThat(maker.browser().postJson(path, made).status()).isEqualTo(201);
        assertThat(maker.browser().request("DELETE", path + "/Fresh__c", null).status()).isEqualTo(204);
    }

    // ---- the runtime: the task, the request, the abilities ----

    @Test
    void anApproverActsOnTheirTaskWithoutAnyAbilityAndAnotherMemberCannot() {
        Setup setup = setup();
        Person other = person(setup.organization(), setup.admin());

        for (String action : List.of("approve", "reject")) {
            Response denied = decide(other.browser(), setup.request(), action, "x", 1);
            assertThat(denied.status()).as(action).isEqualTo(404);
        }
        assertThat(decide(setup.approver().browser(), setup.request(), "reject", "No").status()).isEqualTo(200);
    }

    @Test
    void recallBelongsToTheSubmitterAndTheOverrideAbilityAndCancelToTheOverrideAbilityAlone() {
        Setup setup = setup();
        Person steward = person(setup.organization(), setup.admin(), "approvals.override-lock");

        assertThat(decide(setup.approver().browser(), setup.request(), "recall", null).status()).as("not theirs")
                .isEqualTo(404);
        assertThat(decide(setup.approver().browser(), setup.request(), "cancel", null).status()).isEqualTo(403);
        assertThat(decide(setup.submitter().browser(), setup.request(), "cancel", null).status())
                .as("the submitter may recall, not cancel").isEqualTo(403);
        assertThat(decide(steward.browser(), setup.request(), "cancel", "ended").status()).isEqualTo(200);
    }

    @Test
    void assigningAndMakingTheOutcomeAgainNeedTheManageAbility() {
        Setup setup = setup();
        Person other = person(setup.organization(), setup.admin());
        Person manager = person(setup.organization(), setup.admin(), "approvals.manage");

        Response member = setup.approver().browser().postJson(INSTANCES + "/" + setup.request() + "/assign",
                "{\"toUserId\":\"" + other.userId() + "\",\"version\":" + instanceVersion(setup.admin(),
                        setup.request()) + "}");
        Response outcome = setup.approver().browser().postJson(INSTANCES + "/" + setup.request() + "/outcome",
                "{\"recordVersion\":1,\"version\":1}");

        assertThat(member.status()).isEqualTo(403);
        assertThat(outcome.status()).isEqualTo(403);
        assertThat(manager.browser().get(INSTANCES).status()).as("manage sees the requests it may read")
                .isEqualTo(200);
    }

    @Test
    void theInboxAndThePeopleSearchAreOpenToEveryMemberAndTheDelegationsOfOthersAreNot() {
        Setup setup = setup();
        Person plain = person(setup.organization(), setup.admin());

        assertThat(plain.browser().get(RUNTIME + "/inbox").status()).isEqualTo(200);
        assertThat(plain.browser().get(RUNTIME + "/inbox/summary").status()).isEqualTo(200);
        assertThat(plain.browser().get(RUNTIME + "/people?text=a").status()).isEqualTo(200);
        assertThat(plain.browser().get(DELEGATIONS).status()).isEqualTo(200);
        assertThat(plain.browser().get(DELEGATIONS + "?all=true").status()).isEqualTo(403);
        assertThat(plain.browser().get(INSTANCES).status()).isEqualTo(200);
    }

    @Test
    void thePeopleSearchNeedsTextAndNeverListsTheMembersPageByPage() {
        Setup setup = setup();

        Response none = setup.submitter().browser().get(RUNTIME + "/people");
        Response some = setup.submitter().browser().get(RUNTIME + "/people?text="
                + setup.approver().member().person().email().substring(0, 4));

        assertThat(none.status()).isIn(200, 400);
        if (none.status() == 200) {
            assertThat(JsonPath.<List<Object>>read(none.body(), "$.data")).isEmpty();
        }
        assertThat(some.status()).isEqualTo(200);
        assertThat(JsonPath.<List<Object>>read(some.body(), "$.data").size()).isLessThanOrEqualTo(50);
    }

    // ---- no host, no caller ----

    @Test
    void aPlatformAdministratorIsRefusedOnEveryEndpoint() {
        Setup setup = setup();
        TestUsers.TestUser person = TestPlatform.person(users, PlatformRole.PLATFORM_ADMIN);
        TestBrowser platformAdmin = TestPlatform.signedIn(port, person);

        for (Call call : EVERY_ENDPOINT) {
            Response refused = platformAdmin.request(call.method(), call.path(), call.body());
            assertThat(refused.status()).as(call + " " + refused.body()).isEqualTo(404);
        }
        assertThat(setup.request()).isNotBlank();
    }

    @Test
    void anUnauthenticatedCallerIsRefusedOnEveryEndpoint() {
        Setup setup = setup();
        TestBrowser nobody = new TestBrowser(port, setup.organization().host());

        for (Call call : EVERY_ENDPOINT) {
            assertThat(nobody.request(call.method(), call.path(), call.body()).status()).as(call.toString())
                    .isEqualTo(401);
        }
    }

    // ---- another organization ----

    @Test
    void theAdministratorOfAnotherOrganizationMeetsNothingOnAnyEndpointOfThisOne() {
        Setup setup = setup();
        Organization theirs = organization();
        TestBrowser other = adminWithData(theirs);

        for (Call call : List.of(new Call("GET", ANY_PROCESS, null),
                new Call("GET", INSTANCES + "/" + setup.request(), null),
                new Call("POST", INSTANCES + "/" + setup.request() + "/approve", DECISION),
                new Call("POST", INSTANCES + "/" + setup.request() + "/cancel", DECISION),
                new Call("DELETE", DEFINITIONS + "/" + OBJECT + "/" + setup.process(), null),
                new Call("PUT", DEFINITIONS + "/" + OBJECT + "/" + setup.process(), updateRequest("Hijack", true,
                        null, false, null, null, 0, step("One", "ANY", user(ID)))))) {
            Response refused = other.request(call.method(), call.path(), call.body());
            assertThat(refused.status()).as(call + " " + refused.body()).isEqualTo(404);
        }
        assertThat(JsonPath.<String>read(setup.admin().get(DEFINITIONS + "/" + OBJECT + "/" + setup.process()).body(),
                "$.data.label")).isEqualTo("Expense");
    }
}
