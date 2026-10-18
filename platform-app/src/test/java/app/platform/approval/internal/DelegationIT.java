package app.platform.approval.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations.Organization;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * Delegation (ADR-0151): a person names a delegate for a range of days and the delegate is asked beside them when a
 * step
 * becomes active in that range; no chains, no moving of tasks that exist, one process or all, both days count in the
 * delegation's own time zone, and a member makes their own while someone who manages approvals makes anyone's.
 */
@PlatformIntegrationTest
@TestPropertySource(properties = {
    "platform.outbox.enabled=true",
    "platform.outbox.poll-interval=50ms",
    "platform.workflow.enabled=false"})
class DelegationIT extends ApprovalSupport {

    private record Setup(Organization organization, TestBrowser admin, Person submitter, Person manager,
            Person delegate, String process) {
    }

    private Setup setup() {
        Organization organization = organization();
        TestBrowser admin = adminWithData(organization);
        Person manager = person(organization, admin);
        String process = processExists(admin, "Expense", null, step("Manager", "ANY", user(manager.userId())));
        return new Setup(organization, admin, person(organization, admin), manager, person(organization, admin),
                process);
    }

    private static String delegation(String delegate, String from, String to) {
        return "{\"delegateId\":\"" + delegate + "\",\"startsOn\":\"" + from + "\",\"endsOn\":\"" + to
                + "\",\"timeZone\":\"UTC\"}";
    }

    private static Response delegate(TestBrowser browser, String delegate, String from, String to) {
        return browser.postJson(DELEGATIONS, delegation(delegate, from, to));
    }

    private String freshRequest(Setup setup, String process) {
        return submitted(setup.submitter().browser(), record(setup.submitter().browser(), "{\"fieldA__c\":\"x\"}"),
                process);
    }

    // ---- making, reading, ending ----

    @Test
    void aMemberNamesADelegateSeesItInForceAndEndsItAndTheDelegateIsNoLongerAsked() {
        Setup setup = setup();

        Response made = delegate(setup.manager().browser(), setup.delegate().userId(), today(), inDays(1));
        assertThat(made.status()).as(made.body()).isEqualTo(201);
        String id = JsonPath.read(made.body(), "$.data.id");
        assertThat(JsonPath.<Boolean>read(made.body(), "$.data.inForce")).isTrue();
        assertThat(JsonPath.<String>read(made.body(), "$.data.delegate.userId")).isEqualTo(setup.delegate().userId());
        assertThat(JsonPath.<List<String>>read(setup.manager().browser().get(DELEGATIONS).body(), "$.data[*].id"))
                .containsExactly(id);
        assertThat(delegatedRequests(setup.delegate().browser())).isEmpty();

        String asked = freshRequest(setup, setup.process());
        assertThat(delegatedRequests(setup.delegate().browser())).containsExactly(asked);

        Response ended = setup.manager().browser().request("DELETE", DELEGATIONS + "/" + id, null);
        assertThat(ended.status()).isEqualTo(204);
        assertThat(delegatedRequests(setup.delegate().browser())).as("a task the delegate holds stays")
                .containsExactly(asked);
        String later = freshRequest(setup, setup.process());
        assertThat(delegatedRequests(setup.delegate().browser())).as("a later step is not asked of them")
                .doesNotContain(later);
        assertThat(audited(setup.organization(), "approval.delegation.created")).isEqualTo(1);
        assertThat(audited(setup.organization(), "approval.delegation.revoked")).isEqualTo(1);
    }

    @Test
    void aDelegationThatStartsTomorrowIsNotInForceAndAsksNobodyToday() {
        Setup setup = setup();

        Response made = delegate(setup.manager().browser(), setup.delegate().userId(), inDays(1), inDays(3));
        String asked = freshRequest(setup, setup.process());

        assertThat(made.status()).as(made.body()).isEqualTo(201);
        assertThat(JsonPath.<Boolean>read(made.body(), "$.data.inForce")).isFalse();
        assertThat(delegatedRequests(setup.delegate().browser())).doesNotContain(asked);
    }

    @Test
    void aDelegationMadeAfterTheTaskWasAssignedDoesNotMoveIt() {
        Setup setup = setup();
        String asked = freshRequest(setup, setup.process());

        delegate(setup.manager().browser(), setup.delegate().userId(), today(), inDays(1));

        assertThat(delegatedRequests(setup.delegate().browser())).isEmpty();
        assertThat(openRequests(setup.manager().browser())).containsExactly(asked);
    }

    @Test
    void aDelegationForOneProcessDoesNotReachAnother() {
        Setup setup = setup();
        String other = processExists(setup.admin(), "Travel", null, step("Manager", "ANY",
                user(setup.manager().userId())));
        Response scoped = setup.manager().browser().postJson(DELEGATIONS, "{\"delegateId\":\""
                + setup.delegate().userId() + "\",\"objectApiName\":\"" + OBJECT + "\",\"processApiName\":\""
                + setup.process() + "\",\"startsOn\":\"" + today() + "\",\"endsOn\":\"" + inDays(1)
                + "\",\"timeZone\":\"UTC\"}");
        assertThat(scoped.status()).as(scoped.body()).isEqualTo(201);

        String covered = freshRequest(setup, setup.process());
        String notCovered = freshRequest(setup, other);

        assertThat(delegatedRequests(setup.delegate().browser())).containsExactly(covered)
                .doesNotContain(notCovered);
    }

    @Test
    void thereAreNoChainsADelegateWhoHasDelegatedOnIsNotAskedAgainOnBehalfOfTheFirst() {
        Setup setup = setup();
        Person third = person(setup.organization(), setup.admin());
        delegate(setup.manager().browser(), setup.delegate().userId(), today(), inDays(1));
        delegate(setup.delegate().browser(), third.userId(), today(), inDays(1));

        String asked = freshRequest(setup, setup.process());

        assertThat(delegatedRequests(setup.delegate().browser())).containsExactly(asked);
        assertThat(delegatedRequests(third.browser())).as("a delegate's own delegate is not asked for the manager")
                .isEmpty();
    }

    // ---- refused ----

    @Test
    void aDelegationThatBreaksARuleIsRefusedAtThePlace() {
        Setup setup = setup();
        TestBrowser manager = setup.manager().browser();
        String delegate = setup.delegate().userId();

        Response toSelf = delegate(manager, setup.manager().userId(), today(), inDays(1));
        Response past = delegate(manager, delegate, inDays(-1), inDays(1));
        Response backwards = delegate(manager, delegate, inDays(3), inDays(1));
        Response tooLong = delegate(manager, delegate, today(), inDays(400));
        Response stranger = delegate(manager, java.util.UUID.randomUUID().toString(), today(), inDays(1));
        Response zone = manager.postJson(DELEGATIONS, "{\"delegateId\":\"" + delegate + "\",\"startsOn\":\"" + today()
                + "\",\"endsOn\":\"" + inDays(1) + "\",\"timeZone\":\"Nowhere/Land\"}");
        Response half = manager.postJson(DELEGATIONS, "{\"delegateId\":\"" + delegate + "\",\"objectApiName\":\""
                + OBJECT + "\",\"startsOn\":\"" + today() + "\",\"endsOn\":\"" + inDays(1) + "\"}");
        Response ghost = manager.postJson(DELEGATIONS, "{\"delegateId\":\"" + delegate + "\",\"objectApiName\":\""
                + OBJECT + "\",\"processApiName\":\"Ghost__c\",\"startsOn\":\"" + today() + "\",\"endsOn\":\""
                + inDays(1) + "\"}");

        assertThat(List.of(toSelf, past, backwards, tooLong, stranger, zone, half, ghost)).allSatisfy(
                refused -> assertThat(refused.status()).as(refused.body()).isEqualTo(400));
        assertThat(JsonPath.<java.util.Map<String, Object>>read(toSelf.body(), "$.error.fields")).containsKey(
                "delegateId");
        assertThat(JsonPath.<java.util.Map<String, Object>>read(past.body(), "$.error.fields")).containsKey(
                "startsOn");
        assertThat(JsonPath.<java.util.Map<String, Object>>read(tooLong.body(), "$.error.fields")).containsKey(
                "endsOn");
        assertThat(scalar(setup.organization(), "select count(*) from approval_delegation")).isZero();
    }

    @Test
    void aDelegateWhoHoldsNoLicenceCannotBeNamed() {
        Setup setup = setup();
        setup.admin().request("DELETE", MEMBERS + "/" + setup.delegate().member().membership() + "/licence", null);

        Response refused = delegate(setup.manager().browser(), setup.delegate().userId(), today(), inDays(1));

        assertThat(refused.status()).as(refused.body()).isEqualTo(400);
    }

    @Test
    void aPersonHasAtMostTwentyCurrentOrFutureDelegations() {
        Setup setup = setup();
        for (int i = 0; i < 20; i++) {
            assertThat(delegate(setup.manager().browser(), setup.delegate().userId(), inDays(i), inDays(i)).status())
                    .as("delegation " + i).isEqualTo(201);
        }

        Response one = delegate(setup.manager().browser(), setup.delegate().userId(), inDays(30), inDays(30));

        assertThat(one.status()).as(one.body()).isEqualTo(409);
    }

    // ---- whose they are ----

    @Test
    void aMemberSeesAndEndsOnlyTheirOwnWhileSomeoneWhoManagesApprovalsSeesAndEndsAnyone() {
        Setup setup = setup();
        Response made = delegate(setup.manager().browser(), setup.delegate().userId(), today(), inDays(1));
        String id = JsonPath.read(made.body(), "$.data.id");

        Response others = setup.delegate().browser().get(DELEGATIONS + "?delegator=" + setup.manager().userId());
        Response everyone = setup.delegate().browser().get(DELEGATIONS + "?all=true");
        Response ending = setup.delegate().browser().request("DELETE", DELEGATIONS + "/" + id, null);
        Response asAdministrator = setup.admin().get(DELEGATIONS + "?all=true");

        assertThat(others.status()).isEqualTo(403);
        assertThat(everyone.status()).isEqualTo(403);
        assertThat(ending.status()).as("the delegate is not the delegator").isEqualTo(403);
        assertThat(JsonPath.<List<String>>read(asAdministrator.body(), "$.data[*].id")).containsExactly(id);
        assertThat(setup.admin().request("DELETE", DELEGATIONS + "/" + id, null).status()).isEqualTo(204);
        assertThat(setup.admin().request("DELETE", DELEGATIONS + "/" + id, null).status()).as("already ended")
                .isIn(404, 409);
    }

    @Test
    void anAdministratorCanNameADelegateForAnotherPersonAndAMemberCannot() {
        Setup setup = setup();
        String body = "{\"delegatorId\":\"" + setup.manager().userId() + "\",\"delegateId\":\""
                + setup.delegate().userId() + "\",\"startsOn\":\"" + today() + "\",\"endsOn\":\"" + inDays(1)
                + "\",\"timeZone\":\"UTC\"}";

        Response member = setup.submitter().browser().postJson(DELEGATIONS, body);
        Response administrator = setup.admin().postJson(DELEGATIONS, body);

        assertThat(member.status()).isEqualTo(403);
        assertThat(administrator.status()).as(administrator.body()).isEqualTo(201);
        assertThat(JsonPath.<String>read(administrator.body(), "$.data.delegator.userId"))
                .isEqualTo(setup.manager().userId());
    }
}
