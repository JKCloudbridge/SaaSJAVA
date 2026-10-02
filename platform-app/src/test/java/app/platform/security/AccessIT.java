package app.platform.security;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.identity.PlatformRole;
import app.platform.identity.Users;
import app.platform.licensing.Entitlements;
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
 * Profiles, access policies, roles, individual grants and what members hold (Sprint 7, ADR-0039 to ADR-0045), through
 * the real application and a real PostgreSQL: an administrator sets things up and gives them to members, a member with
 * only some abilities is refused everywhere else, the last free licence has one winner, a role changes no ability, a
 * platform role gives no authority, a forged tenant header changes nothing, and a licence, a permission and a feature
 * entitlement change independently of each other.
 */
@PlatformIntegrationTest
class AccessIT {

    private static final String PROFILES = "/api/v1/profiles";
    private static final String POLICIES = "/api/v1/access-policies";
    private static final String ROLES = "/api/v1/roles";
    private static final String MEMBERS = "/api/v1/members";

    @LocalServerPort
    private int port;

    @Autowired
    private Users users;

    @Autowired
    private Plans plans;

    @Autowired
    private Subscriptions subscriptions;

    @Autowired
    private Entitlements entitlements;

    /** An organization with its administrator, on a plan with the given numbers of user and admin licences. */
    private Organization organization(int userLicences, int adminLicences, String... features) {
        Organization organization = TestOrganizations.create(users);
        TestPlatform.subscribe(plans, subscriptions, organization.id(), userLicences, adminLicences, features);
        return organization;
    }

    private TestBrowser adminOf(Organization organization) {
        return TestOrganizations.signedIn(port, organization.host(), organization.admin().person());
    }

    private TestBrowser as(Organization organization, Member member) {
        return TestOrganizations.signedIn(port, organization.host(), member.person());
    }

    private static String profileJson(String name, String licenceType, String... abilities) {
        return "{\"name\":\"" + name + "\",\"description\":\"\",\"licenceType\":\"" + licenceType
                + "\",\"abilities\":" + array(abilities) + "}";
    }

    private static String policyJson(String name, String licenceType, String... abilities) {
        return "{\"name\":\"" + name + "\",\"description\":\"\",\"abilities\":" + array(abilities)
                + (licenceType == null ? "" : ",\"requiredLicenceType\":\"" + licenceType + "\"") + "}";
    }

    private static String array(String... values) {
        List<String> quoted = new ArrayList<>();
        for (String value : values) {
            quoted.add("\"" + value + "\"");
        }
        return "[" + String.join(",", quoted) + "]";
    }

    private static UUID idOf(Response created) {
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        return UUID.fromString(JsonPath.read(created.body(), "$.data.id"));
    }

    private static UUID createProfile(TestBrowser admin, String name, String licenceType, String... abilities) {
        return idOf(admin.postJson(PROFILES, profileJson(name, licenceType, abilities)));
    }

    private static UUID createPolicy(TestBrowser admin, String name, String licenceType, String... abilities) {
        return idOf(admin.postJson(POLICIES, policyJson(name, licenceType, abilities)));
    }

    private static Response setProfile(TestBrowser admin, UUID membership, UUID profile) {
        return admin.request("PUT", MEMBERS + "/" + membership + "/profile", "{\"profileId\":\"" + profile + "\"}");
    }

    private static Response assignPolicy(TestBrowser admin, UUID membership, UUID policy) {
        return admin.postJson(MEMBERS + "/" + membership + "/policies", "{\"policyId\":\"" + policy + "\"}");
    }

    private static List<String> abilitiesOf(TestBrowser browser) {
        Response me = browser.get("/api/v1/auth/me");
        assertThat(me.status()).isEqualTo(200);
        return JsonPath.read(me.body(), "$.data.abilities");
    }

    private static int assigned(TestBrowser admin, String type) {
        List<Integer> value = JsonPath.read(admin.get("/api/v1/licences").body(),
                "$.data[?(@.licenceType=='" + type + "')].assigned");
        return value.get(0);
    }

    // ---- what exists on the first day ----

    @Test
    void everyOrganizationHasItsTwoSystemProfilesAndTheAbilitiesAreListed() {
        Organization organization = organization(3, 2);
        TestBrowser admin = adminOf(organization);

        Response profiles = admin.get(PROFILES);
        assertThat(profiles.status()).isEqualTo(200);
        assertThat(JsonPath.<List<String>>read(profiles.body(), "$.data[*].name"))
                .containsExactlyInAnyOrder("Organization administrator", "Member");
        assertThat(JsonPath.<List<Boolean>>read(profiles.body(), "$.data[?(@.name=='Member')].defaultProfile"))
                .containsExactly(true);
        assertThat(JsonPath.<List<String>>read(profiles.body(),
                "$.data[?(@.name=='Organization administrator')].licenceType")).containsExactly("admin");
        assertThat(JsonPath.<List<String>>read(admin.get("/api/v1/abilities").body(), "$.data[*].key"))
                .containsExactlyInAnyOrderElementsOf(
                        java.util.Arrays.stream(Ability.values()).map(Ability::key).toList());
        assertThat(abilitiesOf(admin)).containsExactlyInAnyOrderElementsOf(
                java.util.Arrays.stream(Ability.values()).map(Ability::key).toList());
    }

    // ---- the happy path: set up, give, and the change is felt ----

    @Test
    void anAdministratorCreatesAProfileAndAPolicyAndGivesThemToAMember() {
        Organization organization = organization(3, 2);
        TestBrowser admin = adminOf(organization);
        Member person = TestOrganizations.join(users, organization.tenant(), false);
        TestBrowser asPerson = as(organization, person);
        assertThat(abilitiesOf(asPerson)).as("a plain member with no licence has nothing").isEmpty();
        assertThat(asPerson.get(MEMBERS).status()).isEqualTo(403);

        UUID inviter = createProfile(admin, "profile-a", "user", "members.invite");
        assertThat(setProfile(admin, person.membership(), inviter).status()).isEqualTo(204);

        assertThat(abilitiesOf(asPerson)).containsExactly("members.invite");
        assertThat(assigned(admin, "user")).as("the profile took a user licence").isEqualTo(1);
        Response view = admin.get(MEMBERS + "/" + person.membership() + "/access");
        assertThat(JsonPath.<String>read(view.body(), "$.data.profileName")).isEqualTo("profile-a");
        assertThat(JsonPath.<Boolean>read(view.body(), "$.data.licenceHeld")).isTrue();

        UUID viewer = createPolicy(admin, "policy-a", null, "members.view");
        assertThat(assignPolicy(admin, person.membership(), viewer).status()).isEqualTo(204);
        assertThat(abilitiesOf(asPerson)).containsExactlyInAnyOrder("members.invite", "members.view");
        assertThat(asPerson.get(MEMBERS).status()).isEqualTo(200);
        assertThat(asPerson.get("/api/v1/invitations").status()).isEqualTo(200);
        assertThat(asPerson.get("/api/v1/licences").status()).as("not an ability of theirs").isEqualTo(403);

        assertThat(admin.request("DELETE", MEMBERS + "/" + person.membership() + "/policies/" + viewer, null).status())
                .isEqualTo(204);
        assertThat(abilitiesOf(asPerson)).containsExactly("members.invite");
        assertThat(IdentityDb.auditOfType("access.member.profile_set"))
                .anyMatch(record -> organization.id().value().equals(record.tenantId()));
        assertThat(IdentityDb.auditOfType("access.profile.created"))
                .anyMatch(record -> organization.id().value().equals(record.tenantId()));
    }

    @Test
    void anIndividualGrantAddsOneAbilityAndCanBeTakenBack() {
        Organization organization = organization(3, 2);
        TestBrowser admin = adminOf(organization);
        Member person = TestOrganizations.join(users, organization.tenant(), false);
        TestBrowser asPerson = as(organization, person);

        assertThat(admin.postJson(MEMBERS + "/" + person.membership() + "/grants",
                "{\"ability\":\"members.view\",\"reason\":\"covers for a colleague\"}").status()).isEqualTo(204);

        assertThat(abilitiesOf(asPerson)).containsExactly("members.view");
        Response view = admin.get(MEMBERS + "/" + person.membership() + "/access");
        assertThat(JsonPath.<List<String>>read(view.body(), "$.data.grants[*].reason"))
                .containsExactly("covers for a colleague");
        assertThat(admin.postJson(MEMBERS + "/" + person.membership() + "/grants",
                "{\"ability\":\"no.such.ability\"}").status()).as("an unknown ability").isEqualTo(400);

        assertThat(admin.request("DELETE", MEMBERS + "/" + person.membership() + "/grants/members.view", null)
                .status()).isEqualTo(204);
        assertThat(abilitiesOf(asPerson)).isEmpty();
    }

    // ---- licences ----

    @Test
    void aLicenceBoundPolicyUsesALicenceAndAnotherMemberIsRefusedWhenNoneIsFree() {
        Organization organization = organization(3, 3);
        TestBrowser admin = adminOf(organization);
        Member first = TestOrganizations.join(users, organization.tenant(), false);
        Member second = TestOrganizations.join(users, organization.tenant(), false);
        Member third = TestOrganizations.join(users, organization.tenant(), false);
        UUID policy = createPolicy(admin, "policy-a", "admin", "members.view");
        int before = assigned(admin, "admin");

        assertThat(assignPolicy(admin, first.membership(), policy).status()).isEqualTo(204);
        assertThat(assignPolicy(admin, second.membership(), policy).status()).isEqualTo(204);
        assertThat(assigned(admin, "admin")).isEqualTo(before + 2);
        Response refused = assignPolicy(admin, third.membership(), policy);

        assertThat(refused.status()).isEqualTo(409);
        assertThat(JsonPath.<String>read(refused.body(), "$.error.message")).contains("No licence");
        assertThat(admin.request("DELETE", MEMBERS + "/" + first.membership() + "/policies/" + policy, null).status())
                .isEqualTo(204);
        assertThat(assigned(admin, "admin")).as("taking the policy back returns the licence").isEqualTo(before + 1);
        assertThat(assignPolicy(admin, third.membership(), policy).status()).isEqualTo(204);

        assertThat(admin.request("PUT", POLICIES + "/" + policy,
                policyJson("policy-a", "user", "members.view")).status())
                .as("a policy that members hold keeps its licence type").isEqualTo(409);
        assertThat(admin.request("DELETE", POLICIES + "/" + policy, null).status()).isEqualTo(409);
    }

    @Test
    void twoAdministratorsAssigningTheLastFreeLicenceOfAPolicyHaveOneWinner() throws Exception {
        for (int round = 0; round < 3; round++) {
            Organization organization = organization(3, 3);
            Member second = TestOrganizations.join(users, organization.tenant(), true);
            TestBrowser first = adminOf(organization);
            TestBrowser other = as(organization, second);
            UUID policy = createPolicy(first, "policy-a", "admin", "members.view");
            List<Member> candidates = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                candidates.add(TestOrganizations.join(users, organization.tenant(), false));
            }
            int free = 3 - assigned(first, "admin");

            ExecutorService pool = Executors.newFixedThreadPool(candidates.size());
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < candidates.size(); i++) {
                TestBrowser by = i % 2 == 0 ? first : other;
                UUID target = candidates.get(i).membership();
                results.add(pool.submit(() -> {
                    start.await();
                    return assignPolicy(by, target, policy).status();
                }));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : results) {
                statuses.add(result.get());
            }
            pool.shutdown();

            assertThat(statuses.stream().filter(status -> status == 204).count()).as("round " + round).isEqualTo(free);
            assertThat(statuses.stream().filter(status -> status != 204)).as("round " + round).containsOnly(409);
            assertThat(assigned(first, "admin")).isEqualTo(3);
        }
    }

    @Test
    void deactivatingAMemberReturnsEveryLicenceAndReactivationNeverFailsForLackOfOne() {
        Organization organization = organization(1, 3);
        TestBrowser admin = adminOf(organization);
        Member person = TestOrganizations.join(users, organization.tenant(), false);
        Member other = TestOrganizations.join(users, organization.tenant(), false);
        UUID profile = createProfile(admin, "profile-a", "user", "members.view");
        UUID policy = createPolicy(admin, "policy-a", "admin", "members.invite");
        assertThat(setProfile(admin, person.membership(), profile).status()).isEqualTo(204);
        assertThat(assignPolicy(admin, person.membership(), policy).status()).isEqualTo(204);
        int userBefore = assigned(admin, "user");
        int adminBefore = assigned(admin, "admin");
        assertThat(userBefore).isEqualTo(1);

        assertThat(admin.postJson(MEMBERS + "/" + person.membership() + "/deactivate", "{}").status()).isEqualTo(204);

        assertThat(assigned(admin, "user")).as("the profile licence went back").isEqualTo(0);
        assertThat(assigned(admin, "admin")).as("the policy licence went back").isEqualTo(adminBefore - 1);
        // The only user licence is taken by someone else; the person comes back anyway, without a licence.
        assertThat(setProfile(admin, other.membership(), profile).status()).isEqualTo(204);
        assertThat(admin.postJson(MEMBERS + "/" + person.membership() + "/reactivate", "{}").status()).isEqualTo(204);

        Response view = admin.get(MEMBERS + "/" + person.membership() + "/access");
        assertThat(JsonPath.<String>read(view.body(), "$.data.profileName")).as("the default profile, nothing else")
                .isEqualTo("Member");
        assertThat(JsonPath.<Boolean>read(view.body(), "$.data.licenceHeld")).isFalse();
        assertThat(JsonPath.<List<String>>read(view.body(), "$.data.abilities")).as("no policy came back").isEmpty();
        assertThat(setProfile(admin, person.membership(), profile).status()).as("an explicit assignment is strict")
                .isEqualTo(409);
    }

    // ---- roles ----

    @Test
    void theRoleHierarchyRefusesALoopAndChangesNoAbility() {
        Organization organization = organization(3, 2);
        TestBrowser admin = adminOf(organization);
        UUID top = idOf(admin.postJson(ROLES, "{\"name\":\"role-a\",\"description\":\"\"}"));
        UUID middle = idOf(admin.postJson(ROLES, "{\"name\":\"role-b\",\"parentId\":\"" + top + "\"}"));
        UUID bottom = idOf(admin.postJson(ROLES, "{\"name\":\"role-c\",\"parentId\":\"" + middle + "\"}"));

        Response loop = admin.request("PUT", ROLES + "/" + top,
                "{\"name\":\"role-a\",\"parentId\":\"" + bottom + "\"}");
        Response itself = admin.request("PUT", ROLES + "/" + top, "{\"name\":\"role-a\",\"parentId\":\"" + top + "\"}");

        assertThat(loop.status()).isEqualTo(409);
        assertThat(itself.status()).isEqualTo(409);
        assertThat(admin.request("DELETE", ROLES + "/" + top, null).status()).as("it has sub-roles").isEqualTo(409);

        Member person = TestOrganizations.join(users, organization.tenant(), false);
        TestBrowser asPerson = as(organization, person);
        List<String> before = abilitiesOf(asPerson);
        assertThat(admin.request("PUT", MEMBERS + "/" + person.membership() + "/role",
                "{\"roleId\":\"" + bottom + "\"}").status()).isEqualTo(204);
        assertThat(abilitiesOf(asPerson)).as("a role gives no ability").isEqualTo(before);
        assertThat(JsonPath.<String>read(admin.get(MEMBERS + "/" + person.membership() + "/access").body(),
                "$.data.roleName")).isEqualTo("role-c");
        assertThat(admin.request("DELETE", ROLES + "/" + bottom, null).status()).as("a member holds it").isEqualTo(409);
    }

    // ---- who may do what ----

    @Test
    void aMemberWithOnlySomeAbilitiesIsRefusedEverywhereElse() {
        Organization organization = organization(3, 2);
        TestBrowser admin = adminOf(organization);
        Member person = TestOrganizations.join(users, organization.tenant(), false);
        Member target = TestOrganizations.join(users, organization.tenant(), false);
        UUID profile = createProfile(admin, "profile-a", "user", "members.view");
        UUID policy = createPolicy(admin, "policy-a", null, "members.view");
        UUID role = idOf(admin.postJson(ROLES, "{\"name\":\"role-a\"}"));
        assertThat(setProfile(admin, person.membership(), profile).status()).isEqualTo(204);
        TestBrowser asPerson = as(organization, person);
        String member = MEMBERS + "/" + target.membership();

        // allowed: what the ability gives (see members, and the lists that help to choose)
        assertThat(asPerson.get(MEMBERS).status()).isEqualTo(200);
        assertThat(asPerson.get(PROFILES).status()).isEqualTo(200);
        assertThat(asPerson.get(ROLES).status()).isEqualTo(200);
        assertThat(asPerson.get("/api/v1/abilities").status()).isEqualTo(200);
        assertThat(asPerson.get("/api/v1/licence-types").status()).isEqualTo(200);
        // denied: everything else, reads and writes
        List<Response> denied = List.of(
                asPerson.get(POLICIES),
                asPerson.get("/api/v1/invitations"),
                asPerson.get("/api/v1/licences"),
                asPerson.get(member + "/access"),
                asPerson.postJson(PROFILES, profileJson("profile-b", "user")),
                asPerson.request("PUT", PROFILES + "/" + profile, profileJson("profile-b", "user")),
                asPerson.request("DELETE", PROFILES + "/" + profile, null),
                asPerson.postJson(PROFILES + "/" + profile + "/default", "{}"),
                asPerson.postJson(POLICIES, policyJson("policy-b", null)),
                asPerson.request("PUT", POLICIES + "/" + policy, policyJson("policy-b", null)),
                asPerson.request("DELETE", POLICIES + "/" + policy, null),
                asPerson.postJson(ROLES, "{\"name\":\"role-b\"}"),
                asPerson.request("PUT", ROLES + "/" + role, "{\"name\":\"role-b\"}"),
                asPerson.request("DELETE", ROLES + "/" + role, null),
                asPerson.request("PUT", member + "/profile", "{\"profileId\":\"" + profile + "\"}"),
                asPerson.request("PUT", member + "/role", "{\"roleId\":\"" + role + "\"}"),
                asPerson.postJson(member + "/policies", "{\"policyId\":\"" + policy + "\"}"),
                asPerson.request("DELETE", member + "/policies/" + policy, null),
                asPerson.postJson(member + "/grants", "{\"ability\":\"members.view\"}"),
                asPerson.request("DELETE", member + "/grants/members.view", null),
                asPerson.request("PUT", member + "/licence", null),
                asPerson.request("DELETE", member + "/licence", null),
                asPerson.postJson(member + "/deactivate", "{}"),
                asPerson.postJson(member + "/reactivate", "{}"),
                asPerson.postJson("/api/v1/organization/sign-out-all", "{}"),
                asPerson.postJson("/api/v1/invitations", "{\"email\":\"person-a@example.test\"}"));
        for (int i = 0; i < denied.size(); i++) {
            assertThat(denied.get(i).status()).as("denied call " + i).isEqualTo(403);
        }
        assertThat(IdentityDb.auditOfType("membership.action.refused"))
                .anyMatch(record -> "missing_ability".equals(record.reason()));
    }

    @Test
    void aMemberWhoMayInviteCannotGiveAProfileWithAbilitiesTheyLack() {
        Organization organization = organization(3, 2);
        TestBrowser admin = adminOf(organization);
        Member inviter = TestOrganizations.join(users, organization.tenant(), false);
        UUID small = createProfile(admin, "profile-small", "user", "members.invite");
        UUID bigger = createProfile(admin, "profile-big", "user", "members.invite", "members.deactivate");
        UUID role = idOf(admin.postJson(ROLES, "{\"name\":\"role-a\"}"));
        assertThat(setProfile(admin, inviter.membership(), small).status()).isEqualTo(204);
        TestBrowser asInviter = as(organization, inviter);

        Response tooMuch = asInviter.postJson("/api/v1/invitations",
                "{\"email\":\"person-a@example.test\",\"profileId\":\"" + bigger + "\"}");
        Response administrator = asInviter.postJson("/api/v1/invitations",
                "{\"email\":\"person-b@example.test\",\"profileId\":\"" + administratorProfile(admin) + "\"}");
        Response allowed = asInviter.postJson("/api/v1/invitations",
                "{\"email\":\"person-c@example.test\",\"profileId\":\"" + small + "\",\"roleId\":\"" + role + "\"}");
        Response byAdministrator = admin.postJson("/api/v1/invitations",
                "{\"email\":\"person-d@example.test\",\"displayName\":\"Person D\",\"profileId\":\"" + bigger + "\"}");

        assertThat(tooMuch.status()).isEqualTo(403);
        assertThat(administrator.status()).isEqualTo(403);
        assertThat(allowed.status()).isEqualTo(202);
        assertThat(byAdministrator.status()).isEqualTo(202);
        Response listed = admin.get("/api/v1/invitations");
        assertThat(JsonPath.<List<String>>read(listed.body(),
                "$.data[?(@.email=='person-d@example.test')].profileName"))
                .containsExactly("profile-big");
    }

    private static UUID administratorProfile(TestBrowser admin) {
        List<String> ids = JsonPath.read(admin.get(PROFILES).body(),
                "$.data[?(@.name=='Organization administrator')].id");
        return UUID.fromString(ids.get(0));
    }

    // ---- the platform, the organization next door, and a forged header ----

    @Test
    void aPlatformRoleGivesNoAuthorityInsideAnOrganization() {
        Organization organization = organization(3, 2);
        TestUser platformAdmin = TestPlatform.person(users, PlatformRole.PLATFORM_ADMIN);

        // On the organization host a platform person is no member: not even a sign-in.
        assertThat(new TestBrowser(port, organization.host()).signInPassword(platformAdmin.email(),
                platformAdmin.password()).status()).isEqualTo(401);
        // On the platform host there is no organization: every one of these endpoints answers NOT_FOUND.
        TestBrowser onPlatform = TestPlatform.signedIn(port, platformAdmin);
        for (String path : List.of(PROFILES, POLICIES, ROLES, "/api/v1/abilities", "/api/v1/licence-types", MEMBERS,
                MEMBERS + "/" + organization.admin().membership() + "/access")) {
            assertThat(onPlatform.get(path).status()).as(path).isEqualTo(404);
        }
        assertThat(onPlatform.postJson(PROFILES, profileJson("profile-a", "user")).status()).isEqualTo(404);
        assertThat(onPlatform.postJson(POLICIES, policyJson("policy-a", null)).status()).isEqualTo(404);
        assertThat(onPlatform.postJson(ROLES, "{\"name\":\"role-a\"}").status()).isEqualTo(404);
        assertThat(onPlatform.postJson("/api/v1/organization/leave", "{}").status()).isEqualTo(404);
        assertThat(abilitiesOf(onPlatform)).isEmpty();
    }

    @Test
    void anIdentifierOfAnotherOrganizationIsSimplyNotFound() {
        Organization mine = organization(3, 2);
        Organization theirs = organization(3, 2);
        TestBrowser admin = adminOf(mine);
        TestBrowser theirAdmin = adminOf(theirs);
        UUID theirProfile = createProfile(theirAdmin, "profile-a", "user", "members.view");
        UUID theirPolicy = createPolicy(theirAdmin, "policy-a", null, "members.view");
        UUID theirRole = idOf(theirAdmin.postJson(ROLES, "{\"name\":\"role-a\"}"));
        Member mineMember = TestOrganizations.join(users, mine.tenant(), false);
        Member theirMember = TestOrganizations.join(users, theirs.tenant(), false);

        assertThat(setProfile(admin, mineMember.membership(), theirProfile).status()).isEqualTo(404);
        assertThat(assignPolicy(admin, mineMember.membership(), theirPolicy).status()).isEqualTo(404);
        assertThat(admin.request("PUT", MEMBERS + "/" + mineMember.membership() + "/role",
                "{\"roleId\":\"" + theirRole + "\"}").status()).isEqualTo(404);
        assertThat(admin.request("PUT", PROFILES + "/" + theirProfile, profileJson("x", "user")).status())
                .isEqualTo(404);
        assertThat(admin.request("DELETE", POLICIES + "/" + theirPolicy, null).status()).isEqualTo(404);
        assertThat(admin.request("DELETE", ROLES + "/" + theirRole, null).status()).isEqualTo(404);
        assertThat(setProfile(admin, theirMember.membership(), createProfile(admin, "profile-b", "user")).status())
                .isEqualTo(404);
        assertThat(admin.get(MEMBERS + "/" + theirMember.membership() + "/access").status()).isEqualTo(404);
        assertThat(admin.postJson("/api/v1/invitations", "{\"email\":\"person-a@example.test\",\"profileId\":\""
                + theirProfile + "\"}").status()).as("a foreign profile on an invitation").isEqualTo(400);
        assertThat(JsonPath.<List<String>>read(admin.get(PROFILES).body(), "$.data[*].name"))
                .as("nothing of theirs is listed").doesNotContain("profile-a");
    }

    @Test
    void aForgedTenantHeaderChangesNothingForReadsAndWrites() {
        Organization mine = organization(3, 2);
        Organization theirs = organization(3, 2);
        TestBrowser admin = adminOf(mine);
        String[] forged = {"X-Tenant-Id", theirs.id().value().toString(), "X-Forwarded-Host", theirs.host(),
                "X-Organization", theirs.tenant().slug()};
        List<String> reads = List.of(PROFILES, POLICIES, ROLES, "/api/v1/abilities", "/api/v1/licence-types", MEMBERS,
                MEMBERS + "/" + mine.admin().membership() + "/access", "/api/v1/auth/me");
        for (String path : reads) {
            Response plain = admin.get(path);
            Response withHeaders = admin.get(path, forged);
            assertThat(withHeaders.status()).as(path).isEqualTo(plain.status());
            assertThat(strip(withHeaders.body())).as(path).isEqualTo(strip(plain.body()));
        }

        Response created = admin.request("POST", PROFILES, profileJson("profile-forged", "user"), forged);
        UUID profile = idOf(created);
        assertThat(JsonPath.<List<String>>read(admin.get(PROFILES).body(), "$.data[*].name"))
                .contains("profile-forged");
        assertThat(JsonPath.<List<String>>read(adminOf(theirs).get(PROFILES).body(), "$.data[*].name"))
                .as("the write landed in the organization of the host only").doesNotContain("profile-forged");
        assertThat(admin.request("DELETE", PROFILES + "/" + profile, null, forged).status()).isEqualTo(204);
    }

    private static String strip(String body) {
        return body.replaceAll("\"requestId\":\"[^\"]*\"", "-").replaceAll("\"traceId\":\"[^\"]*\"", "-");
    }

    // ---- the last member who can manage access ----

    @Test
    void theLastMemberWhoCanManageAccessCannotBeRemovedInAnyWay() {
        Organization organization = organization(3, 2);
        TestBrowser admin = adminOf(organization);
        UUID self = organization.admin().membership();
        UUID memberProfile = JsonPath.<List<String>>read(admin.get(PROFILES).body(), "$.data[?(@.name=='Member')].id")
                .stream().map(UUID::fromString).findFirst().orElseThrow();

        Response deactivate = admin.postJson(MEMBERS + "/" + self + "/deactivate", "{}");
        Response demote = setProfile(admin, self, memberProfile);
        Response leave = admin.postJson("/api/v1/organization/leave", "{}");
        Response release = admin.request("DELETE", MEMBERS + "/" + self + "/licence", null);

        for (Response refused : List.of(deactivate, demote, leave, release)) {
            assertThat(refused.status()).as(refused.body()).isEqualTo(409);
            assertThat(JsonPath.<String>read(refused.body(), "$.error.message")).contains("manage access");
        }
        assertThat(abilitiesOf(admin)).as("nothing changed").contains("access.manage");

        // With a second holder the first can step down; the second is then the last.
        Member second = TestOrganizations.join(users, organization.tenant(), true);
        assertThat(admin.postJson(MEMBERS + "/" + self + "/deactivate", "{}").status()).isEqualTo(204);
        assertThat(as(organization, second).postJson("/api/v1/organization/leave", "{}").status()).isEqualTo(409);
    }

    @Test
    void aCustomProfileCanCarryManageAccessAndThenTheAdministratorMayStepDown() {
        Organization organization = organization(3, 2);
        TestBrowser admin = adminOf(organization);
        UUID manager = createProfile(admin, "profile-a", "user", "access.manage", "members.view");
        Member person = TestOrganizations.join(users, organization.tenant(), false);
        assertThat(setProfile(admin, person.membership(), manager).status()).isEqualTo(204);

        assertThat(admin.postJson(MEMBERS + "/" + organization.admin().membership() + "/deactivate", "{}").status())
                .isEqualTo(204);
        TestBrowser asPerson = as(organization, person);
        assertThat(asPerson.get(POLICIES).status()).isEqualTo(200);
        // Taking the ability out of the profile would remove the last holder.
        Response edit = asPerson.request("PUT", PROFILES + "/" + manager,
                profileJson("profile-a", "user", "members.view"));
        assertThat(edit.status()).isEqualTo(409);
        assertThat(asPerson.get(POLICIES).status()).as("still a holder").isEqualTo(200);
    }

    // ---- three separate mechanisms ----

    @Test
    void aLicenceAPermissionAndAFeatureEntitlementChangeIndependentlyOfEachOther() {
        Organization organization = organization(3, 2, "approvals");
        TestBrowser admin = adminOf(organization);
        Member person = TestOrganizations.join(users, organization.tenant(), false);
        UUID profile = createProfile(admin, "profile-a", "user", "members.view");
        assertThat(setProfile(admin, person.membership(), profile).status()).isEqualTo(204);
        TestBrowser asPerson = as(organization, person);
        assertThat(abilitiesOf(asPerson)).containsExactly("members.view");
        assertThat(entitlements.enabled(organization.id(), "approvals")).isTrue();

        // Taking the licence back removes what the profile gave and touches nothing else.
        assertThat(admin.request("DELETE", MEMBERS + "/" + person.membership() + "/licence", null).status())
                .isEqualTo(204);
        assertThat(abilitiesOf(asPerson)).as("no licence, no abilities from the profile").isEmpty();
        assertThat(JsonPath.<String>read(admin.get(MEMBERS + "/" + person.membership() + "/access").body(),
                "$.data.profileName")).as("the profile is still recorded").isEqualTo("profile-a");

        // Changing the profile's abilities, or the plan's features, does not move the licence or the other mechanism.
        assertThat(admin.request("PUT", MEMBERS + "/" + person.membership() + "/licence", null).status())
                .isEqualTo(204);
        assertThat(abilitiesOf(asPerson)).containsExactly("members.view");
        assertThat(admin.request("PUT", PROFILES + "/" + profile, profileJson("profile-a", "user", "members.invite"))
                .status()).isEqualTo(200);
        assertThat(abilitiesOf(asPerson)).containsExactly("members.invite");
        assertThat(assigned(admin, "user")).as("editing a profile does not touch a licence").isEqualTo(1);
        assertThat(entitlements.enabled(organization.id(), "approvals")).as("nor a feature").isTrue();
    }

    // ---- profiles, in detail ----

    @Test
    void theSystemProfilesStayAndAProfileInUseIsNotRemoved() {
        Organization organization = organization(3, 2);
        TestBrowser admin = adminOf(organization);
        UUID administrator = administratorProfile(admin);
        UUID memberProfile = JsonPath.<List<String>>read(admin.get(PROFILES).body(), "$.data[?(@.name=='Member')].id")
                .stream().map(UUID::fromString).findFirst().orElseThrow();
        UUID custom = createProfile(admin, "profile-a", "user", "members.view");
        Member person = TestOrganizations.join(users, organization.tenant(), false);
        assertThat(setProfile(admin, person.membership(), custom).status()).isEqualTo(204);

        assertThat(admin.request("DELETE", PROFILES + "/" + administrator, null).status()).isEqualTo(409);
        assertThat(admin.request("DELETE", PROFILES + "/" + memberProfile, null).status()).isEqualTo(409);
        assertThat(admin.request("PUT", PROFILES + "/" + administrator, profileJson("x", "admin")).status())
                .as("the administrator profile cannot be changed").isEqualTo(409);
        assertThat(admin.postJson(PROFILES + "/" + administrator + "/default", "{}").status()).isEqualTo(409);
        assertThat(admin.request("DELETE", PROFILES + "/" + custom, null).status()).as("a member holds it")
                .isEqualTo(409);
        assertThat(admin.request("PUT", PROFILES + "/" + custom, profileJson("profile-a", "admin", "members.view"))
                .status()).as("its licence type is kept while members hold it").isEqualTo(409);
        assertThat(admin.postJson(PROFILES, profileJson("PROFILE-A", "user")).status())
                .as("names are unique, whatever the case").isEqualTo(400);
        assertThat(admin.postJson(PROFILES, profileJson("profile-b", "no-such-type")).status()).isEqualTo(400);
        assertThat(admin.postJson(PROFILES, profileJson("profile-b", "user", "no.such.ability")).status())
                .isEqualTo(400);

        assertThat(admin.postJson(PROFILES + "/" + custom + "/default", "{}").status()).isEqualTo(204);
        assertThat(admin.request("DELETE", PROFILES + "/" + custom, null).status()).as("it is the default now")
                .isEqualTo(409);
        assertThat(setProfile(admin, person.membership(), memberProfile).status()).isEqualTo(204);
        assertThat(admin.postJson(PROFILES + "/" + memberProfile + "/default", "{}").status()).isEqualTo(204);
        assertThat(admin.request("DELETE", PROFILES + "/" + custom, null).status()).isEqualTo(204);
    }

    @Test
    void aMemberCanLeaveByThemselvesAndAnAdministratorLetsThemBack() {
        Organization organization = organization(3, 2);
        TestBrowser admin = adminOf(organization);
        Member person = TestOrganizations.join(users, organization.tenant(), false);
        UUID profile = createProfile(admin, "profile-a", "user", "members.view");
        assertThat(setProfile(admin, person.membership(), profile).status()).isEqualTo(204);
        TestBrowser asPerson = as(organization, person);
        assertThat(assigned(admin, "user")).isEqualTo(1);

        assertThat(asPerson.postJson("/api/v1/organization/leave", "{}").status()).isEqualTo(204);

        assertThat(asPerson.get("/api/v1/auth/me").status()).as("their session ended").isEqualTo(401);
        assertThat(assigned(admin, "user")).as("their licence went back").isEqualTo(0);
        assertThat(JsonPath.<List<String>>read(admin.get(MEMBERS).body(),
                "$.data[?(@.id=='" + person.membership() + "')].status")).containsExactly("DEACTIVATED");
        assertThat(IdentityDb.auditOfType("membership.left"))
                .anyMatch(record -> organization.id().value().equals(record.tenantId()));
        assertThat(admin.postJson(MEMBERS + "/" + person.membership() + "/reactivate", "{}").status())
                .isEqualTo(204);
        assertThat(abilitiesOf(as(organization, person))).as("back with the default profile only").isEmpty();
    }
}
