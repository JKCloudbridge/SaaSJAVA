package app.platform.approval.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations;
import app.platform.testsupport.TestOrganizations.Member;
import app.platform.testsupport.TestOrganizations.Organization;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * What approvals must never give away (ADR-0146, ADR-0152): a task is not access to a record, the history and the panel
 * never carry a value of the record, a request is invisible to people with no business with it, and one organization
 * never sees, decides or starts anything of another, whatever headers the caller forges.
 */
@PlatformIntegrationTest
@TestPropertySource(properties = {
    "platform.outbox.enabled=true",
    "platform.outbox.poll-interval=50ms",
    "platform.workflow.enabled=false"})
class ApprovalSecurityIT extends ApprovalSupport {

    private static final String SECRET = "SECRET-VALUE-OF-THE-RECORD";

    /** A licensed member whose profile gives no access to any object at all. */
    private Person blind(Organization organization, TestBrowser admin) {
        Member joined = TestOrganizations.join(users, organization.tenant(), false);
        Response profile = admin.postJson(PROFILES, "{\"name\":\"blind-" + UUID.randomUUID() + "\","
                + "\"description\":\"\",\"licenceType\":\"user\",\"abilities\":[]}");
        assertThat(profile.status()).as(profile.body()).isEqualTo(201);
        Response set = admin.request("PUT", MEMBERS + "/" + joined.membership() + "/profile",
                "{\"profileId\":\"" + JsonPath.<String>read(profile.body(), "$.data.id") + "\"}");
        assertThat(set.status()).as(set.body()).isIn(200, 204);
        return new Person(joined, TestOrganizations.signedIn(port, organization.host(), joined.person()));
    }

    // ---- a task is not access ----

    @Test
    void anApproverWhoCannotReadTheRecordDecidesWithoutEverGainingAccessToIt() {
        Organization organization = organization();
        TestBrowser admin = adminWithData(organization);
        Person submitter = person(organization, admin);
        Person approver = blind(organization, admin);
        String process = processExists(admin, "Expense", null, step("One", "ANY", user(approver.userId())));
        String record = record(submitter.browser(), "{\"fieldA__c\":\"" + SECRET + "\"}");
        String request = submitted(submitter.browser(), record, process);

        Response inbox = approver.browser().get(RUNTIME + "/inbox?tab=TO_APPROVE");
        assertThat(JsonPath.<List<String>>read(inbox.body(), "$.data[*].instance.id")).containsExactly(request);
        assertThat(JsonPath.<Boolean>read(inbox.body(), "$.data[0].recordReadable")).isFalse();
        assertThat(optional(inbox.body(), "$.data[0].recordTitle")).as("no title of a record they cannot read")
                .isNull();
        assertThat(inbox.body()).doesNotContain(SECRET);
        assertThat(approver.browser().get(RECORDS + "/" + record).status()).isEqualTo(404);
        assertThat(approver.browser().get(RECORDS + "/" + record + "/approval").status()).isEqualTo(404);

        assertThat(approve(approver.browser(), request).status()).as("the task is theirs").isEqualTo(200);

        assertThat(approver.browser().get(RECORDS + "/" + record).status()).as("and still no access").isEqualTo(404);
        assertThat(statusOf(admin, request)).isEqualTo("APPROVED");
    }

    @Test
    void noAnswerOfTheApprovalApiCarriesAValueOfTheRecord() {
        Organization organization = organization();
        TestBrowser admin = adminWithData(organization);
        Person submitter = person(organization, admin);
        // Blind: a sighted approver would legitimately be shown the record's title (the inbox's own rule), which
        // would be this same literal if it were the title field. A value is never carried regardless of who reads it.
        Person approver = blind(organization, admin);
        String process = processExists(admin, createRequest("Expense", null, false, null,
                outcomes(assignment("fieldC__c", "Approved"), "", ""),
                step("One", "ANY", user(approver.userId()))));
        String record = record(submitter.browser(), "{\"fieldA__c\":\"" + SECRET + "\",\"fieldB__c\":\"" + SECRET
                + "\"}");
        String request = submitted(submitter.browser(), record, process);

        List<Response> answers = List.of(submitter.browser().get(RECORDS + "/" + record + "/approval"),
                instance(submitter.browser(), request), approver.browser().get(RUNTIME + "/inbox"),
                approver.browser().get(RUNTIME + "/inbox/summary"), admin.get(INSTANCES),
                approve(approver.browser(), request), instance(admin, request));

        for (Response answer : answers) {
            assertThat(answer.body()).as(answer.body()).doesNotContain(SECRET);
        }
        assertThat(owner("select attributes::text from audit_record where event_type like 'approval.%' and "
                + "context_tenant_id = ?", organization.id().value())).allSatisfy(
                        attributes -> assertThat(attributes).doesNotContain(SECRET));
    }

    // ---- who sees a request ----

    @Test
    void aRequestAndItsHistoryAreSeenOnlyByTheSubmitterThoseAskedAndTheMembersWhoMaySeeAll() {
        Organization organization = organization();
        TestBrowser admin = adminWithData(organization);
        Person submitter = person(organization, admin);
        Person approver = person(organization, admin);
        Person stranger = person(organization, admin);
        Person auditor = person(organization, admin, "approvals.view-all");
        String process = processExists(admin, "Expense", null, step("One", "ANY", user(approver.userId())));
        String request = submitted(submitter.browser(), record(submitter.browser(), "{\"fieldA__c\":\"x\"}"),
                process);

        assertThat(instance(submitter.browser(), request).status()).isEqualTo(200);
        assertThat(instance(approver.browser(), request).status()).isEqualTo(200);
        assertThat(instance(auditor.browser(), request).status()).isEqualTo(200);
        Response hidden = instance(stranger.browser(), request);
        Response missing = instance(stranger.browser(), UUID.randomUUID().toString());
        assertThat(hidden.status()).isEqualTo(404);
        assertThat(JsonPath.<String>read(hidden.body(), "$.error.message")).as("not-found is one answer")
                .isEqualTo(JsonPath.<String>read(missing.body(), "$.error.message"));
        assertThat(JsonPath.<List<String>>read(stranger.browser().get(INSTANCES).body(), "$.data[*].id")).isEmpty();
        assertThat(JsonPath.<List<String>>read(submitter.browser().get(INSTANCES).body(), "$.data[*].id"))
                .containsExactly(request);
        assertThat(JsonPath.<List<String>>read(auditor.browser().get(INSTANCES).body(), "$.data[*].id"))
                .containsExactly(request);
    }

    @Test
    void aMemberWhoMaySeeEverythingStillCannotSeeARequestForARecordTheyCannotRead() {
        Organization organization = organization();
        TestBrowser admin = adminWithData(organization);
        Person submitter = person(organization, admin);
        Person approver = person(organization, admin);
        Person blindAuditor = blind(organization, admin);
        Response grant = admin.postJson(MEMBERS + "/" + blindAuditor.member().membership() + "/grants",
                "{\"ability\":\"approvals.view-all\"}");
        assertThat(grant.status()).as(grant.body()).isIn(200, 201, 204);
        String process = processExists(admin, "Expense", null, step("One", "ANY", user(approver.userId())));
        String request = submitted(submitter.browser(), record(submitter.browser(), "{\"fieldA__c\":\"x\"}"),
                process);

        assertThat(instance(blindAuditor.browser(), request).status()).isEqualTo(404);
        assertThat(JsonPath.<List<String>>read(blindAuditor.browser().get(INSTANCES).body(), "$.data[*].id"))
                .isEmpty();
    }

    @Test
    void aMemberWhoCannotEditTheRecordCannotSubmitItAndIsToldNothingAboutIt() {
        Organization organization = organization();
        TestBrowser admin = adminWithData(organization);
        Person owner = person(organization, admin);
        Person approver = person(organization, admin);
        Person blind = blind(organization, admin);
        String process = processExists(admin, "Expense", null, step("One", "ANY", user(approver.userId())));
        String record = record(owner.browser(), "{\"fieldA__c\":\"x\"}");

        Response refused = submit(blind.browser(), record, process, null);

        assertThat(refused.status()).as(refused.body()).isEqualTo(404);
        assertThat(scalar(organization, "select count(*) from approval_instance")).isZero();
    }

    // ---- other organizations ----

    @Test
    void anotherOrganizationNeverSeesDecidesOrStartsAnythingOfThisOne() {
        Organization mine = organization();
        Organization theirs = organization();
        TestBrowser admin = adminWithData(mine);
        TestBrowser other = adminWithData(theirs);
        Person submitter = person(mine, admin);
        Person approver = person(mine, admin);
        String process = processExists(admin, "Expense", null, step("One", "ANY", user(approver.userId())));
        String record = record(submitter.browser(), "{\"fieldA__c\":\"x\"}");
        String request = submitted(submitter.browser(), record, process);

        assertThat(instance(other, request).status()).isEqualTo(404);
        for (String action : List.of("approve", "reject", "recall", "cancel")) {
            assertThat(decide(other, request, action, "x", 1).status()).as(action).isEqualTo(404);
        }
        assertThat(submit(other, record, process, null).status()).isEqualTo(404);
        assertThat(other.get(DEFINITIONS + "/" + OBJECT + "/" + process).status()).isEqualTo(404);
        assertThat(JsonPath.<List<String>>read(other.get(INSTANCES).body(), "$.data[*].id")).isEmpty();
        assertThat(JsonPath.<List<String>>read(other.get(DEFINITIONS).body(), "$.data[*].apiName")).isEmpty();
        assertThat(statusOf(admin, request)).isEqualTo("PENDING");
    }

    @Test
    void aForgedTenantHeaderChangesNothing() {
        Organization mine = organization();
        Organization theirs = organization();
        TestBrowser admin = adminWithData(mine);
        TestBrowser other = adminWithData(theirs);
        Person approver = person(theirs, other);
        Person submitter = person(theirs, other);
        String process = processExists(other, "Secret", null, step("One", "ANY", user(approver.userId())));
        String request = submitted(submitter.browser(), record(submitter.browser(), "{\"fieldA__c\":\"x\"}"),
                process);
        String[] forged = {"X-Tenant-Id", theirs.id().value().toString(), "X-Forwarded-Host", theirs.host(),
            "X-Organization", theirs.tenant().slug()};

        Response definitions = admin.get(DEFINITIONS, forged);
        Response requests = admin.get(INSTANCES, forged);
        Response one = admin.get(INSTANCES + "/" + request, forged);

        assertThat(definitions.body()).doesNotContain("Secret");
        assertThat(JsonPath.<List<String>>read(requests.body(), "$.data[*].id")).isEmpty();
        assertThat(one.status()).isEqualTo(404);
    }
}
