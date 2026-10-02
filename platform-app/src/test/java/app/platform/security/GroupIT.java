package app.platform.security;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.identity.PlatformRole;
import app.platform.identity.Users;
import app.platform.licensing.Plans;
import app.platform.licensing.Subscriptions;
import app.platform.testsupport.IdentityDb;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations;
import app.platform.testsupport.TestOrganizations.Member;
import app.platform.testsupport.TestOrganizations.Organization;
import app.platform.testsupport.TestPlatform;
import app.platform.testsupport.TestUsers.TestUser;
import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Public groups (Sprint 8, ADR-0047) through the real application and a real PostgreSQL: people and nested groups,
 * what a group gives (the abilities of its access policies, to everyone in it, also through nesting), that taking
 * someone out or removing a group takes effect at once, loops refused in the service and in the database under
 * concurrency, the last member who can manage access staying when the ability arrives through a group, a policy that
 * needs a licence refused for a group, and the usual authorization checks: a member with only some abilities, a
 * platform
 * person, another organization's identifiers and a forged tenant header.
 */
@PlatformIntegrationTest
class GroupIT {

    private static final String GROUPS = "/api/v1/groups";
    private static final String POLICIES = "/api/v1/access-policies";
    private static final String PROFILES = "/api/v1/profiles";
    private static final String MEMBERS = "/api/v1/members";

    @LocalServerPort
    private int port;

    @Autowired
    private Users users;

    @Autowired
    private Plans plans;

    @Autowired
    private Subscriptions subscriptions;

    private Organization organization() {
        Organization organization = TestOrganizations.create(users);
        TestPlatform.subscribe(plans, subscriptions, organization.id(), 5, 3);
        return organization;
    }

    private TestBrowser adminOf(Organization organization) {
        return TestOrganizations.signedIn(port, organization.host(), organization.admin().person());
    }

    private TestBrowser as(Organization organization, Member member) {
        return TestOrganizations.signedIn(port, organization.host(), member.person());
    }

    private static UUID idOf(Response created) {
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        return UUID.fromString(JsonPath.read(created.body(), "$.data.id"));
    }

    private static UUID createGroup(TestBrowser admin, String name) {
        return idOf(admin.postJson(GROUPS, "{\"name\":\"" + name + "\",\"description\":\"\"}"));
    }

    private static UUID createPolicy(TestBrowser admin, String name, String licenceType, String... abilities) {
        List<String> quoted = new ArrayList<>();
        for (String ability : abilities) {
            quoted.add("\"" + ability + "\"");
        }
        return idOf(admin.postJson(POLICIES, "{\"name\":\"" + name + "\",\"description\":\"\",\"abilities\":["
                + String.join(",", quoted) + "]" + (licenceType == null ? "" : ",\"requiredLicenceType\":\""
                + licenceType + "\"") + "}"));
    }

    private static Response addPerson(TestBrowser admin, UUID group, UUID membership) {
        return admin.postJson(GROUPS + "/" + group + "/members", "{\"membershipId\":\"" + membership + "\"}");
    }

    private static Response addGroup(TestBrowser admin, UUID group, UUID inner) {
        return admin.postJson(GROUPS + "/" + group + "/members", "{\"groupId\":\"" + inner + "\"}");
    }

    private static Response givePolicy(TestBrowser admin, UUID group, UUID policy) {
        return admin.postJson(GROUPS + "/" + group + "/policies", "{\"policyId\":\"" + policy + "\"}");
    }

    private static List<String> abilitiesOf(TestBrowser browser) {
        Response me = browser.get("/api/v1/auth/me");
        assertThat(me.status()).isEqualTo(200);
        return JsonPath.read(me.body(), "$.data.abilities");
    }

    // ---- what a group gives ----

    @Test
    void everyoneInAGroupHoldsWhatItsAccessPolicyGivesAndLeavingTakesItAwayAtOnce() {
        Organization organization = organization();
        TestBrowser admin = adminOf(organization);
        Member person = TestOrganizations.join(users, organization.tenant(), false);
        TestBrowser asPerson = as(organization, person);
        UUID group = createGroup(admin, "group-a");
        UUID policy = createPolicy(admin, "policy-a", null, "members.view");
        assertThat(abilitiesOf(asPerson)).isEmpty();

        assertThat(givePolicy(admin, group, policy).status()).isEqualTo(200);
        assertThat(abilitiesOf(asPerson)).as("not in the group yet").isEmpty();
        Response added = addPerson(admin, group, person.membership());
        assertThat(added.status()).isEqualTo(200);
        assertThat(JsonPath.<List<String>>read(added.body(), "$.data.people[*]"))
                .containsExactly(person.membership().toString());

        assertThat(abilitiesOf(asPerson)).containsExactly("members.view");
        assertThat(asPerson.get(MEMBERS).status()).isEqualTo(200);
        Response access = admin.get(MEMBERS + "/" + person.membership() + "/access");
        assertThat(JsonPath.<List<String>>read(access.body(), "$.data.groups[*].name")).containsExactly("group-a");
        assertThat(JsonPath.<List<Boolean>>read(access.body(), "$.data.groups[*].direct")).containsExactly(true);
        assertThat(JsonPath.<List<String>>read(access.body(), "$.data.policies[*].name"))
                .as("the policy is the group's, not the member's own").isEmpty();

        assertThat(admin.request("DELETE", GROUPS + "/" + group + "/members/people/" + person.membership(), null)
                .status()).isEqualTo(200);
        assertThat(abilitiesOf(asPerson)).as("taken out of the group").isEmpty();
        assertThat(addPerson(admin, group, person.membership()).status()).isEqualTo(200);
        assertThat(abilitiesOf(asPerson)).containsExactly("members.view");
        assertThat(admin.request("DELETE", GROUPS + "/" + group + "/policies/" + policy, null).status())
                .isEqualTo(200);
        assertThat(abilitiesOf(asPerson)).as("the policy taken from the group").isEmpty();
        assertThat(IdentityDb.auditOfType("access.group.created"))
                .anyMatch(record -> organization.id().value().equals(record.tenantId()));
        assertThat(IdentityDb.auditOfType("access.group.member_added"))
                .anyMatch(record -> organization.id().value().equals(record.tenantId()));
        assertThat(IdentityDb.auditOfType("access.group.policy_given"))
                .anyMatch(record -> organization.id().value().equals(record.tenantId()));
    }

    @Test
    void nestedGroupsPassWhatTheyGiveDownAndRemovingAGroupTakesItAwayAtOnce() {
        Organization organization = organization();
        TestBrowser admin = adminOf(organization);
        Member person = TestOrganizations.join(users, organization.tenant(), false);
        TestBrowser asPerson = as(organization, person);
        UUID outer = createGroup(admin, "group-a");
        UUID middle = createGroup(admin, "group-b");
        UUID inner = createGroup(admin, "group-c");
        UUID policy = createPolicy(admin, "policy-a", null, "members.view");
        assertThat(givePolicy(admin, outer, policy).status()).isEqualTo(200);
        assertThat(addGroup(admin, outer, middle).status()).isEqualTo(200);
        assertThat(addGroup(admin, middle, inner).status()).isEqualTo(200);

        assertThat(addPerson(admin, inner, person.membership()).status()).isEqualTo(200);

        assertThat(abilitiesOf(asPerson)).as("two levels down").containsExactly("members.view");
        Response access = admin.get(MEMBERS + "/" + person.membership() + "/access");
        assertThat(JsonPath.<List<String>>read(access.body(), "$.data.groups[*].name"))
                .containsExactlyInAnyOrder("group-a", "group-b", "group-c");
        assertThat(JsonPath.<List<String>>read(access.body(), "$.data.groups[?(@.direct==true)].name"))
                .containsExactly("group-c");

        assertThat(admin.request("DELETE", GROUPS + "/" + middle + "/members/groups/" + inner, null).status())
                .isEqualTo(200);
        assertThat(abilitiesOf(asPerson)).as("the chain is cut").isEmpty();
        assertThat(addGroup(admin, middle, inner).status()).isEqualTo(200);
        assertThat(abilitiesOf(asPerson)).containsExactly("members.view");
        assertThat(admin.request("DELETE", GROUPS + "/" + middle, null).status()).isEqualTo(204);
        assertThat(abilitiesOf(asPerson)).as("the middle group is gone").isEmpty();
        assertThat(admin.get(GROUPS + "/" + middle).status()).isEqualTo(404);
        assertThat(JsonPath.<List<String>>read(admin.get(GROUPS + "/" + outer).body(), "$.data.groups[*]"))
                .as("it is no longer inside the outer group").isEmpty();
    }

    @Test
    void aMemberInTwoGroupsThatGiveTheSameThingHoldsItOnceAndStaysWhileOneRemains() {
        Organization organization = organization();
        TestBrowser admin = adminOf(organization);
        Member person = TestOrganizations.join(users, organization.tenant(), false);
        TestBrowser asPerson = as(organization, person);
        UUID first = createGroup(admin, "group-a");
        UUID second = createGroup(admin, "group-b");
        UUID policy = createPolicy(admin, "policy-a", null, "members.view");
        for (UUID group : List.of(first, second)) {
            givePolicy(admin, group, policy);
            addPerson(admin, group, person.membership());
        }

        assertThat(abilitiesOf(asPerson)).containsExactly("members.view");
        admin.request("DELETE", GROUPS + "/" + first + "/members/people/" + person.membership(), null);
        assertThat(abilitiesOf(asPerson)).as("still in the other group").containsExactly("members.view");
        admin.request("DELETE", GROUPS + "/" + second + "/members/people/" + person.membership(), null);
        assertThat(abilitiesOf(asPerson)).isEmpty();
    }

    @Test
    void deactivatingAMemberTakesThemOutOfEveryGroup() {
        Organization organization = organization();
        TestBrowser admin = adminOf(organization);
        Member person = TestOrganizations.join(users, organization.tenant(), false);
        UUID group = createGroup(admin, "group-a");
        addPerson(admin, group, person.membership());

        assertThat(admin.postJson(MEMBERS + "/" + person.membership() + "/deactivate", "{}").status())
                .isEqualTo(204);

        assertThat(JsonPath.<List<String>>read(admin.get(GROUPS + "/" + group).body(), "$.data.people[*]"))
                .isEmpty();
        assertThat(addPerson(admin, group, person.membership()).status()).as("not an active member")
                .isEqualTo(409);
    }

    // ---- rules ----

    @Test
    void aGroupCannotContainItselfDirectlyOrThroughOtherGroups() {
        Organization organization = organization();
        TestBrowser admin = adminOf(organization);
        UUID first = createGroup(admin, "group-a");
        UUID second = createGroup(admin, "group-b");
        UUID third = createGroup(admin, "group-c");

        Response itself = addGroup(admin, first, first);
        assertThat(itself.status()).isEqualTo(409);
        assertThat(JsonPath.<String>read(itself.body(), "$.error.message")).contains("contain itself");
        assertThat(addGroup(admin, first, second).status()).isEqualTo(200);
        assertThat(addGroup(admin, second, third).status()).isEqualTo(200);
        assertThat(addGroup(admin, second, first).status()).as("a two-step loop").isEqualTo(409);
        assertThat(addGroup(admin, third, first).status()).as("a three-step loop").isEqualTo(409);
        assertThat(addGroup(admin, first, third).status()).as("not a loop, only a second path").isEqualTo(200);
    }

    @Test
    void twoAdministratorsMakingTwoGroupsContainEachOtherAtTheSameMomentHaveOneWinner() throws Exception {
        for (int round = 0; round < 5; round++) {
            Organization organization = organization();
            Member second = TestOrganizations.join(users, organization.tenant(), true);
            TestBrowser first = adminOf(organization);
            TestBrowser other = as(organization, second);
            UUID groupA = createGroup(first, "group-a");
            UUID groupB = createGroup(first, "group-b");

            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch start = new CountDownLatch(1);
            Future<Integer> one = pool.submit(() -> {
                start.await();
                return addGroup(first, groupA, groupB).status();
            });
            Future<Integer> two = pool.submit(() -> {
                start.await();
                return addGroup(other, groupB, groupA).status();
            });
            start.countDown();
            List<Integer> results = List.of(one.get(), two.get());
            pool.shutdown();

            assertThat(results).as("round " + round).containsExactlyInAnyOrder(200, 409);
        }
    }

    @Test
    void aPolicyThatNeedsALicenceCannotBeGivenToAGroupAndAPolicyAGroupUsesIsKept() {
        Organization organization = organization();
        TestBrowser admin = adminOf(organization);
        UUID group = createGroup(admin, "group-a");
        UUID licenceBound = createPolicy(admin, "policy-a", "admin", "members.view");
        UUID plain = createPolicy(admin, "policy-b", null, "members.view");

        Response refused = givePolicy(admin, group, licenceBound);
        assertThat(refused.status()).isEqualTo(409);
        assertThat(JsonPath.<String>read(refused.body(), "$.error.message")).contains("needs a licence");
        assertThat(givePolicy(admin, group, plain).status()).isEqualTo(200);

        assertThat(admin.request("DELETE", POLICIES + "/" + plain, null).status()).as("a group uses it")
                .isEqualTo(409);
        assertThat(admin.request("PUT", POLICIES + "/" + plain, "{\"name\":\"policy-b\",\"description\":\"\","
                + "\"abilities\":[\"members.view\"],\"requiredLicenceType\":\"user\"}").status())
                .as("it keeps its licence type while a group uses it").isEqualTo(409);
        assertThat(JsonPath.<List<Integer>>read(admin.get(POLICIES).body(), "$.data[?(@.name=='policy-b')].groups"))
                .containsExactly(1);
    }

    @Test
    void namesAreUniqueAndAGroupCanBeRenamed() {
        Organization organization = organization();
        TestBrowser admin = adminOf(organization);
        UUID group = createGroup(admin, "group-a");

        assertThat(admin.postJson(GROUPS, "{\"name\":\"GROUP-A\",\"description\":\"\"}").status()).isEqualTo(400);
        assertThat(admin.request("PUT", GROUPS + "/" + group, "{\"name\":\"group-z\",\"description\":\"d\"}")
                .status()).isEqualTo(200);
        assertThat(JsonPath.<List<String>>read(admin.get(GROUPS).body(), "$.data[*].name")).containsExactly("group-z");
        assertThat(admin.postJson(GROUPS, "{\"name\":\"\"}").status()).isEqualTo(400);
    }

    // ---- the last member who can manage access, also through a group ----

    @Test
    void theLastMemberWhoCanManageAccessCannotBeRemovedWhenTheAbilityComesThroughAGroup() {
        Organization organization = organization();
        TestBrowser admin = adminOf(organization);
        Member manager = TestOrganizations.join(users, organization.tenant(), false);
        UUID policy = createPolicy(admin, "policy-a", null, "access.manage");
        UUID group = createGroup(admin, "group-a");
        assertThat(givePolicy(admin, group, policy).status()).isEqualTo(200);
        assertThat(addPerson(admin, group, manager.membership()).status()).isEqualTo(200);
        TestBrowser asManager = as(organization, manager);
        assertThat(abilitiesOf(asManager)).contains("access.manage");

        // The administrator may step down: the group's member still holds the ability.
        assertThat(admin.postJson(MEMBERS + "/" + organization.admin().membership() + "/deactivate", "{}")
                .status()).isEqualTo(204);

        // Now every way of removing the last holder is refused.
        List<Response> refused = List.of(
                asManager.request("DELETE", GROUPS + "/" + group + "/members/people/" + manager.membership(), null),
                asManager.request("DELETE", GROUPS + "/" + group + "/policies/" + policy, null),
                asManager.request("DELETE", GROUPS + "/" + group, null),
                asManager.postJson("/api/v1/organization/leave", "{}"),
                asManager.request("PUT", POLICIES + "/" + policy, "{\"name\":\"policy-a\",\"description\":\"\","
                        + "\"abilities\":[\"members.view\"]}"));
        for (Response response : refused) {
            assertThat(response.status()).as(response.body()).isEqualTo(409);
            assertThat(JsonPath.<String>read(response.body(), "$.error.message")).contains("manage access");
        }
        assertThat(abilitiesOf(asManager)).as("nothing changed").contains("access.manage");
    }

    @Test
    void twoManagersRemovingEachOthersGroupAccessAtTheSameMomentLeaveOne() throws Exception {
        for (int round = 0; round < 3; round++) {
            Organization organization = organization();
            TestBrowser admin = adminOf(organization);
            Member one = TestOrganizations.join(users, organization.tenant(), false);
            Member two = TestOrganizations.join(users, organization.tenant(), false);
            UUID policy = createPolicy(admin, "policy-a", null, "access.manage");
            UUID groupOne = createGroup(admin, "group-a");
            UUID groupTwo = createGroup(admin, "group-b");
            givePolicy(admin, groupOne, policy);
            givePolicy(admin, groupTwo, policy);
            addPerson(admin, groupOne, one.membership());
            addPerson(admin, groupTwo, two.membership());
            // The administrator steps down so that the two group members are the only holders.
            assertThat(admin.postJson(MEMBERS + "/" + organization.admin().membership() + "/deactivate", "{}")
                    .status()).isEqualTo(204);
            TestBrowser asOne = as(organization, one);
            TestBrowser asTwo = as(organization, two);

            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch start = new CountDownLatch(1);
            Future<Integer> first = pool.submit(() -> {
                start.await();
                return asOne.request("DELETE", GROUPS + "/" + groupOne + "/members/people/" + one.membership(), null)
                        .status();
            });
            Future<Integer> second = pool.submit(() -> {
                start.await();
                return asTwo.request("DELETE", GROUPS + "/" + groupTwo + "/members/people/" + two.membership(), null)
                        .status();
            });
            start.countDown();
            List<Integer> results = List.of(first.get(), second.get());
            pool.shutdown();

            assertThat(results).as("round " + round).containsExactlyInAnyOrder(200, 409);
        }
    }

    // ---- authorization ----

    @Test
    void aMemberWithOnlySomeAbilitiesIsRefusedOnEveryGroupEndpoint() {
        Organization organization = organization();
        TestBrowser admin = adminOf(organization);
        Member person = TestOrganizations.join(users, organization.tenant(), false);
        UUID viewer = idOf(admin.postJson(PROFILES, "{\"name\":\"profile-a\",\"description\":\"\","
                + "\"licenceType\":\"user\",\"abilities\":[\"members.view\",\"members.invite\"]}"));
        assertThat(admin.request("PUT", MEMBERS + "/" + person.membership() + "/profile",
                "{\"profileId\":\"" + viewer + "\"}").status()).isEqualTo(204);
        TestBrowser asPerson = as(organization, person);
        assertThat(asPerson.get(MEMBERS).status()).as("they do hold something").isEqualTo(200);
        UUID group = createGroup(admin, "group-a");
        UUID policy = createPolicy(admin, "policy-a", null, "members.view");
        String one = GROUPS + "/" + group;

        List<Response> denied = List.of(
                asPerson.get(GROUPS),
                asPerson.get(one),
                asPerson.postJson(GROUPS, "{\"name\":\"group-b\"}"),
                asPerson.request("PUT", one, "{\"name\":\"group-b\"}"),
                asPerson.request("DELETE", one, null),
                asPerson.postJson(one + "/members", "{\"membershipId\":\"" + person.membership() + "\"}"),
                asPerson.request("DELETE", one + "/members/people/" + person.membership(), null),
                asPerson.request("DELETE", one + "/members/groups/" + group, null),
                asPerson.postJson(one + "/policies", "{\"policyId\":\"" + policy + "\"}"),
                asPerson.request("DELETE", one + "/policies/" + policy, null));
        for (int i = 0; i < denied.size(); i++) {
            assertThat(denied.get(i).status()).as("denied call " + i).isEqualTo(403);
        }
        assertThat(IdentityDb.auditOfType("access.action.refused"))
                .anyMatch(record -> "missing_ability".equals(record.reason()));
    }

    @Test
    void aPlatformAdministratorHasNoAuthorityOverGroups() {
        Organization organization = organization();
        TestUser platformAdmin = TestPlatform.person(users, PlatformRole.PLATFORM_ADMIN);
        TestBrowser onPlatform = TestPlatform.signedIn(port, platformAdmin);
        UUID group = createGroup(adminOf(organization), "group-a");

        assertThat(onPlatform.get(GROUPS).status()).isEqualTo(404);
        assertThat(onPlatform.get(GROUPS + "/" + group).status()).isEqualTo(404);
        assertThat(onPlatform.postJson(GROUPS, "{\"name\":\"group-b\"}").status()).isEqualTo(404);
        assertThat(onPlatform.request("DELETE", GROUPS + "/" + group, null).status()).isEqualTo(404);
        assertThat(new TestBrowser(port, organization.host()).signInPassword(platformAdmin.email(),
                platformAdmin.password()).status()).as("no sign-in on an organization host").isEqualTo(401);
    }

    @Test
    void anIdentifierOfAnotherOrganizationIsSimplyNotFound() {
        Organization mine = organization();
        Organization theirs = organization();
        TestBrowser admin = adminOf(mine);
        TestBrowser theirAdmin = adminOf(theirs);
        UUID myGroup = createGroup(admin, "group-a");
        UUID theirGroup = createGroup(theirAdmin, "group-a");
        UUID theirPolicy = createPolicy(theirAdmin, "policy-a", null, "members.view");
        Member theirMember = TestOrganizations.join(users, theirs.tenant(), false);

        assertThat(admin.get(GROUPS + "/" + theirGroup).status()).isEqualTo(404);
        assertThat(admin.request("PUT", GROUPS + "/" + theirGroup, "{\"name\":\"x\"}").status()).isEqualTo(404);
        assertThat(admin.request("DELETE", GROUPS + "/" + theirGroup, null).status()).isEqualTo(404);
        assertThat(addGroup(admin, myGroup, theirGroup).status()).as("a foreign group inside mine").isEqualTo(404);
        assertThat(addGroup(admin, theirGroup, myGroup).status()).isEqualTo(404);
        assertThat(addPerson(admin, myGroup, theirMember.membership()).status()).as("a foreign member")
                .isEqualTo(404);
        assertThat(givePolicy(admin, myGroup, theirPolicy).status()).as("a foreign policy").isEqualTo(404);
        assertThat(JsonPath.<List<String>>read(admin.get(GROUPS).body(), "$.data[*].id"))
                .containsExactly(myGroup.toString());
    }

    @Test
    void aForgedTenantHeaderChangesNothingForReadsAndWrites() {
        Organization mine = organization();
        Organization theirs = organization();
        TestBrowser admin = adminOf(mine);
        TestBrowser theirAdmin = adminOf(theirs);
        UUID group = createGroup(admin, "group-a");
        createGroup(theirAdmin, "group-theirs");
        String[] forged = {"X-Tenant-Id", theirs.id().value().toString(), "X-Forwarded-Host", theirs.host(),
                "X-Organization", theirs.tenant().slug()};

        for (String path : List.of(GROUPS, GROUPS + "/" + group)) {
            Response plain = admin.get(path);
            Response withHeaders = admin.get(path, forged);
            assertThat(withHeaders.status()).as(path).isEqualTo(plain.status());
            assertThat(strip(withHeaders.body())).as(path).isEqualTo(strip(plain.body()));
        }
        UUID created = idOf(admin.request("POST", GROUPS, "{\"name\":\"group-forged\"}", forged));
        assertThat(JsonPath.<List<String>>read(admin.get(GROUPS).body(), "$.data[*].name"))
                .contains("group-forged");
        assertThat(JsonPath.<List<String>>read(theirAdmin.get(GROUPS).body(), "$.data[*].name"))
                .as("the write landed in the organization of the host only").doesNotContain("group-forged");
        assertThat(admin.request("DELETE", GROUPS + "/" + created, null, forged).status()).isEqualTo(204);
    }

    private static String strip(String body) {
        return body.replaceAll("\"requestId\":\"[^\"]*\"", "-").replaceAll("\"traceId\":\"[^\"]*\"", "-");
    }
}
