package app.platform.approval.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.sharedkernel.ActorId;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations.Organization;
import app.platform.testsupport.TestPlatform;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * The plan feature {@code approvals} (ADR-0152), kept apart from the licence and from the permission: without it no
 * process can be made and no request can start; a request that is already pending is a promise to the people asked, so
 * it goes on to its end and keeps its lock; when the feature comes back everything is as it was.
 */
@PlatformIntegrationTest
@TestPropertySource(properties = {
    "platform.outbox.enabled=true",
    "platform.outbox.poll-interval=50ms",
    "platform.workflow.enabled=false"})
class ApprovalEntitlementIT extends ApprovalSupport {

    private void regainTheFeature(Organization organization) {
        String key = TestPlatform.plan(plans, 30, Math.max(3, TestPlatform.adminLicencesInUse(organization.id())),
                "approvals", "workflows");
        subscriptions.change(organization.id(), new app.platform.licensing.SubscriptionChange(key, null, null, null),
                ActorId.SYSTEM);
    }

    @Test
    void withoutTheFeatureNoProcessCanBeMade() {
        Organization organization = organizationWithoutTheFeature();
        TestBrowser admin = adminOf(organization);
        Person approver = person(organization, admin);

        Response refused = createProcess(admin, "Expense", null, step("One", "ANY", user(approver.userId())));

        assertThat(refused.status()).as(refused.body()).isEqualTo(409);
        assertThat(code(refused)).isEqualTo("CONFLICT");
        assertThat(scalar(organization, "select count(*) from approval_process")).isZero();
    }

    @Test
    void whenTheFeatureIsLostNothingNewStartsButAPendingRequestFinishesAndKeepsItsLock() {
        Organization organization = organization();
        TestBrowser admin = adminWithData(organization);
        Person submitter = person(organization, admin);
        Person approver = person(organization, admin);
        String process = processExists(admin, "Expense", null, step("One", "ANY", user(approver.userId())));
        String waiting = record(submitter.browser(), "{\"fieldA__c\":\"one\"}");
        String fresh = record(submitter.browser(), "{\"fieldA__c\":\"two\"}");
        String request = submitted(submitter.browser(), waiting, process);

        loseTheFeature(organization);

        Response refused = submit(submitter.browser(), fresh, process, null);
        assertThat(refused.status()).as(refused.body()).isEqualTo(409);
        assertThat(JsonPath.<String>read(refused.body(), "$.error.message")).contains("plan");
        assertThat(change(submitter.browser(), waiting, "{\"fieldA__c\":\"x\"}").status()).as("the lock holds")
                .isEqualTo(409);
        assertThat(openRequests(approver.browser())).containsExactly(request);
        assertThat(approve(approver.browser(), request).status()).isEqualTo(200);
        assertThat(statusOf(admin, request)).isEqualTo("APPROVED");
        assertThat(change(submitter.browser(), waiting, "{\"fieldA__c\":\"x\"}").status()).as("and opens at the end")
                .isEqualTo(200);
    }

    @Test
    void withoutTheFeatureAProcessCannotBeSwitchedOnButOneCanBeSwitchedOffAndRemoved() {
        Organization organization = organization();
        TestBrowser admin = adminWithData(organization);
        Person approver = person(organization, admin);
        String process = processExists(admin, "Expense", null, step("One", "ANY", user(approver.userId())));
        String path = DEFINITIONS + "/" + OBJECT + "/" + process;
        loseTheFeature(organization);

        Response off = admin.postJson(path + "/activation", "{\"active\":false,\"version\":"
                + processVersion(admin, process) + "}");
        Response on = admin.postJson(path + "/activation", "{\"active\":true,\"version\":"
                + processVersion(admin, process) + "}");
        Response removed = admin.request("DELETE", path, null);

        assertThat(off.status()).as(off.body()).isEqualTo(200);
        assertThat(on.status()).as(on.body()).isEqualTo(409);
        assertThat(removed.status()).as(removed.body()).isEqualTo(204);
    }

    @Test
    void theHeaderLinkIsOfferedOnlyToPeopleWhoHaveSomethingToDoWithApprovals() {
        Organization organization = organization();
        TestBrowser admin = adminWithData(organization);
        Person submitter = person(organization, admin);
        Person approver = person(organization, admin);
        Person stranger = person(organization, admin);
        String process = processExists(admin, "Expense", null, step("One", "ANY", user(approver.userId())));
        submitted(submitter.browser(), record(submitter.browser(), "{\"fieldA__c\":\"one\"}"), process);

        loseTheFeature(organization);

        assertThat(JsonPath.<Boolean>read(stranger.browser().get(RUNTIME + "/inbox/summary").body(),
                "$.data.available")).isFalse();
        Response asked = approver.browser().get(RUNTIME + "/inbox/summary");
        assertThat(JsonPath.<Boolean>read(asked.body(), "$.data.available")).as("they have a task").isTrue();
        assertThat(JsonPath.<Integer>read(asked.body(), "$.data.openTasks")).isEqualTo(1);
    }

    @Test
    void whenTheFeatureComesBackTheProcessesWorkAgain() {
        Organization organization = organization();
        TestBrowser admin = adminWithData(organization);
        Person submitter = person(organization, admin);
        Person approver = person(organization, admin);
        String process = processExists(admin, "Expense", null, step("One", "ANY", user(approver.userId())));
        loseTheFeature(organization);
        String record = record(submitter.browser(), "{\"fieldA__c\":\"one\"}");
        assertThat(submit(submitter.browser(), record, process, null).status()).isEqualTo(409);

        regainTheFeature(organization);

        assertThat(submit(submitter.browser(), record, process, null).status()).isEqualTo(201);
    }

    @Test
    void theFeatureIsNotThePermissionAndNotTheLicence() {
        Organization organization = organization();
        TestBrowser admin = adminWithData(organization);
        Person plain = person(organization, admin);

        Response definitions = plain.browser().get(DEFINITIONS);

        assertThat(definitions.status()).as("the plan has it, the member does not").isEqualTo(403);
    }
}
