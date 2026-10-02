package app.platform.licensing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.platform.identity.Users;
import app.platform.sharedkernel.ActorId;
import app.platform.sharedkernel.TenantId;
import app.platform.testsupport.IdentityDb;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations;
import app.platform.testsupport.TestOrganizations.Member;
import app.platform.testsupport.TestOrganizations.Organization;
import app.platform.testsupport.TestPlatform;
import app.platform.testsupport.TestSignIn;
import app.platform.testsupport.tenancy.TenantFixtures;
import com.jayway.jsonpath.JsonPath;
import java.sql.SQLException;
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
 * Licences (Sprint 6, ADR-0032; since Sprint 7 they follow profiles, ADR-0039): an organization's administrators give
 * and take back the licence a member's profile needs, the last free licence has one winner under concurrency, a pool
 * cannot be reduced below use (service and database), a deactivated member gives the licence back, and a licence, an
 * entitlement and a permission are three separate things. The allowed, denied and cross-organization cases of every
 * endpoint.
 */
@PlatformIntegrationTest
class LicencesIT {

    private static final String POOLS = "/api/v1/licences";

    @LocalServerPort
    private int port;

    @Autowired
    private Users users;

    @Autowired
    private Plans plans;

    @Autowired
    private Subscriptions subscriptions;

    @Autowired
    private Licences licences;

    @Autowired
    private Entitlements entitlements;

    @Autowired
    private app.platform.tenant.TenantContexts contexts;

    private Organization organization(int userLicences, int adminLicences, String... features) {
        Organization organization = TestOrganizations.create(users);
        TestPlatform.subscribe(plans, subscriptions, organization.id(), userLicences, adminLicences, features);
        return organization;
    }

    private TestBrowser adminOf(Organization organization) {
        return TestOrganizations.signedIn(port, organization.host(), organization.admin().person());
    }

    /** Gives the member the licence their profile needs (the member holds the default profile unless given another). */
    private static Response give(TestBrowser admin, UUID membership) {
        return admin.request("PUT", "/api/v1/members/" + membership + "/licence", null);
    }

    private static Response release(TestBrowser admin, UUID membership) {
        return admin.request("DELETE", "/api/v1/members/" + membership + "/licence", null);
    }

    private static UUID createProfile(TestBrowser admin, String name, String licenceType, String... abilities) {
        List<String> quoted = new ArrayList<>();
        for (String ability : abilities) {
            quoted.add("\"" + ability + "\"");
        }
        Response created = admin.postJson("/api/v1/profiles", "{\"name\":\"" + name + "\",\"description\":\"\","
                + "\"licenceType\":\"" + licenceType + "\",\"abilities\":[" + String.join(",", quoted) + "]}");
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        return UUID.fromString(JsonPath.read(created.body(), "$.data.id"));
    }

    private static Response setProfile(TestBrowser admin, UUID membership, UUID profile) {
        return admin.request("PUT", "/api/v1/members/" + membership + "/profile",
                "{\"profileId\":\"" + profile + "\"}");
    }

    private static int assigned(TestBrowser admin, String type) {
        Response pools = admin.get(POOLS);
        assertThat(pools.status()).isEqualTo(200);
        List<Integer> value = JsonPath.read(pools.body(), "$.data[?(@.licenceType=='" + type + "')].assigned");
        return value.get(0);
    }

    private static String licenceOf(TestBrowser admin, UUID membership) {
        Response members = admin.get("/api/v1/members");
        assertThat(members.status()).isEqualTo(200);
        List<String> value = JsonPath.read(members.body(), "$.data[?(@.id=='" + membership + "')].licence");
        return value.isEmpty() ? null : value.get(0);
    }

    // ---- the happy path and the numbers ----

    @Test
    void anAdministratorSeesTheNumbersGivesAndTakesBackALicence() {
        Organization organization = organization(2, 1);
        TestBrowser admin = adminOf(organization);
        Member other = TestOrganizations.join(users, organization.tenant(), false);

        Response pools = admin.get(POOLS);
        assertThat(pools.status()).isEqualTo(200);
        assertThat(JsonPath.<List<Integer>>read(pools.body(), "$.data[?(@.licenceType=='user')].quantity"))
                .containsExactly(2);
        assertThat(assigned(admin, "user")).isZero();
        assertThat(licenceOf(admin, other.membership())).as("a member holds none until one is given").isNull();

        assertThat(give(admin, other.membership()).status()).isEqualTo(204);

        assertThat(assigned(admin, "user")).isEqualTo(1);
        assertThat(JsonPath.<List<Integer>>read(admin.get(POOLS).body(), "$.data[?(@.licenceType=='user')].available"))
                .containsExactly(1);
        assertThat(licenceOf(admin, other.membership())).isEqualTo("user");
        assertThat(IdentityDb.auditOfType("membership.licence.assigned"))
                .anyMatch(record -> organization.id().value().equals(record.tenantId()));

        assertThat(release(admin, other.membership()).status()).isEqualTo(204);

        assertThat(assigned(admin, "user")).isZero();
        assertThat(licenceOf(admin, other.membership())).isNull();
        assertThat(release(admin, other.membership()).status()).as("releasing again changes nothing").isEqualTo(204);
    }

    @Test
    void givingAMemberAnotherProfileMovesTheirLicenceToTheTypeOfThatProfile() {
        Organization organization = organization(2, 2);
        TestBrowser admin = adminOf(organization);
        Member other = TestOrganizations.join(users, organization.tenant(), false);
        assertThat(give(admin, other.membership()).status()).isEqualTo(204);
        UUID adminTypeProfile = createProfile(admin, "profile-a", "admin", "members.view");

        assertThat(setProfile(admin, other.membership(), adminTypeProfile).status()).isEqualTo(204);

        assertThat(licenceOf(admin, other.membership())).isEqualTo("admin");
        assertThat(assigned(admin, "user")).isZero();
        assertThat(assigned(admin, "admin")).isEqualTo(2);
        assertThat(setProfile(admin, other.membership(), UUID.randomUUID()).status()).as("an unknown profile")
                .isEqualTo(404);
    }

    @Test
    void anOrganizationWithoutAFreeLicenceIsRefusedWithAConflict() {
        Organization organization = organization(1, 1);
        TestBrowser admin = adminOf(organization);
        Member first = TestOrganizations.join(users, organization.tenant(), false);
        Member second = TestOrganizations.join(users, organization.tenant(), false);
        UUID adminTypeProfile = createProfile(admin, "profile-a", "admin");
        assertThat(give(admin, first.membership()).status()).isEqualTo(204);

        Response noneFree = give(admin, second.membership());
        Response noAdminLicence = setProfile(admin, second.membership(), adminTypeProfile);

        assertThat(noneFree.status()).isEqualTo(409);
        assertThat(noneFree.body()).contains("No licence of this type is free.");
        assertThat(noAdminLicence.status()).as("the only admin licence is the administrator's").isEqualTo(409);
    }

    // ---- who may ask, and for whom ----

    @Test
    void aMemberWithoutTheAbilityIsRefusedEverywhere() {
        Organization organization = organization(2, 1);
        Member plain = TestOrganizations.join(users, organization.tenant(), false);
        TestBrowser member = TestOrganizations.signedIn(port, organization.host(), plain.person());

        assertThat(member.get(POOLS).status()).isEqualTo(403);
        assertThat(give(member, plain.membership()).status()).isEqualTo(403);
        assertThat(release(member, plain.membership()).status()).isEqualTo(403);
        assertThat(member.postJson("/api/v1/organization/sign-out-all", "{}").status()).isEqualTo(403);
    }

    @Test
    void anAdministratorCannotReachAnotherOrganizationsMembersOrTheirLicences() {
        Organization mine = organization(2, 1);
        Organization other = organization(5, 1);
        Member theirMember = TestOrganizations.join(users, other.tenant(), false);
        TestBrowser admin = adminOf(mine);

        assertThat(give(admin, theirMember.membership()).status()).as("a foreign member is not found")
                .isEqualTo(404);
        assertThat(release(admin, theirMember.membership()).status()).isEqualTo(404);
        assertThat(assigned(adminOf(other), "user")).as("nothing changed over there").isZero();
        // A forged tenant header changes nothing: the organization is the host. The other organization holds 5 user
        // licences and this one 2, so the numbers say which organization answered.
        String bearer = admin.signIn(mine.admin().person().email(), mine.admin().person().password()).bearer();
        Response forged = new TestHttp(port).get(POOLS, "Host", mine.host(), "X-Tenant-Id", other.id().toString(),
                "X-Forwarded-Host", other.host(), "Authorization", bearer);
        assertThat(forged.status()).isEqualTo(200);
        assertThat(JsonPath.<List<Integer>>read(forged.body(), "$.data[?(@.licenceType=='user')].quantity"))
                .containsExactly(2);
    }

    @Test
    void theLicenceEndpointsAnswerOnlyOnAnOrganizationHostAndNeedASignIn() {
        Organization organization = organization(2, 1);
        Member plain = TestOrganizations.join(users, organization.tenant(), false);
        TestBrowser onPlatformHost = new TestBrowser(port, TestSignIn.PLATFORM_HOST);
        String bearer = TestSignIn.bearerOnPlatformHost(port, users);

        assertThat(new TestHttp(port, "Host", TestSignIn.PLATFORM_HOST).get(POOLS, "Authorization", bearer).status())
                .as("the platform host has no organization").isEqualTo(404);
        assertThat(new TestHttp(port, "Host", TestSignIn.PLATFORM_HOST).request("PUT",
                "/api/v1/members/" + plain.membership() + "/licence", null,
                "Authorization", bearer, "Content-Type", "application/json").status()).isEqualTo(404);
        assertThat(new TestHttp(port, "Host", organization.host()).get(POOLS).status()).as("no sign-in").isEqualTo(401);
        assertThat(onPlatformHost.get(POOLS).status()).isEqualTo(401);
    }

    // ---- concurrency and the database guards ----

    @Test
    void twoAdministratorsGivingTheLastFreeLicenceHaveOneWinner() throws Exception {
        for (int round = 0; round < 3; round++) {
            Organization organization = organization(1, 3);
            Member second = TestOrganizations.join(users, organization.tenant(), true);
            List<Member> candidates = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                candidates.add(TestOrganizations.join(users, organization.tenant(), false));
            }
            TestBrowser first = adminOf(organization);
            TestBrowser other = TestOrganizations.signedIn(port, organization.host(), second.person());

            ExecutorService pool = Executors.newFixedThreadPool(candidates.size());
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < candidates.size(); i++) {
                TestBrowser by = i % 2 == 0 ? first : other;
                UUID target = candidates.get(i).membership();
                results.add(pool.submit(() -> {
                    start.await();
                    return give(by, target).status();
                }));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : results) {
                statuses.add(result.get());
            }
            pool.shutdown();

            assertThat(statuses).as("round " + round).containsOnlyOnce(204).filteredOn(s -> s != 204)
                    .containsOnly(409);
            assertThat(assigned(first, "user")).isEqualTo(1);
        }
    }

    @Test
    void aPoolCannotBeReducedBelowUseNeitherByTheServiceNorByTheDatabase() throws SQLException {
        Organization organization = organization(3, 1);
        TestBrowser admin = adminOf(organization);
        Member a = TestOrganizations.join(users, organization.tenant(), false);
        Member b = TestOrganizations.join(users, organization.tenant(), false);
        assertThat(give(admin, a.membership()).status()).isEqualTo(204);
        assertThat(give(admin, b.membership()).status()).isEqualTo(204);

        assertThatThrownBy(() -> inOrganization(organization.id(),
                () -> licences.setPoolQuantity("user", 1, ActorId.SYSTEM)))
                .hasMessageContaining("below the licences that are in use");
        inOrganization(organization.id(), () -> licences.setPoolQuantity("user", 2, ActorId.SYSTEM));
        assertThat(JsonPath.<List<Integer>>read(admin.get(POOLS).body(), "$.data[?(@.licenceType=='user')].quantity"))
                .containsExactly(2);

        // The same rule in the database, as the application role, whatever the code does.
        assertThatThrownBy(() -> TenantFixtures.asTenant(organization.id(), connection -> TenantFixtures.update(
                connection, "update licence_pool set quantity = 1, version = version + 1 "
                        + "where licence_type_id = (select id from licence_type where key = 'user')")))
                .hasMessageContaining("reduced below");
        // And an assignment beyond the pool is refused by the database too.
        Member c = TestOrganizations.join(users, organization.tenant(), false);
        assertThatThrownBy(() -> TenantFixtures.asTenant(organization.id(), connection -> TenantFixtures.update(
                connection, "insert into licence_assignment (tenant_id, membership_id, licence_type_id, created_by, "
                        + "updated_by) select ?, ?, id, ?, ? from licence_type where key = 'user'",
                organization.id().value(), c.membership(), ActorId.SYSTEM.value(), ActorId.SYSTEM.value())))
                .hasMessageContaining("no free licence");
    }

    // ---- the life of a membership ----

    @Test
    void deactivatingAMemberGivesTheLicenceBackAndReactivationBringsTheDefaultOneWhenFree() {
        Organization organization = organization(2, 1);
        TestBrowser admin = adminOf(organization);
        Member other = TestOrganizations.join(users, organization.tenant(), false);
        assertThat(give(admin, other.membership()).status()).isEqualTo(204);
        assertThat(assigned(admin, "user")).isEqualTo(1);

        assertThat(admin.postJson("/api/v1/members/" + other.membership() + "/deactivate", "{}").status())
                .isEqualTo(204);

        assertThat(assigned(admin, "user")).as("the licence went back to the pool").isZero();
        assertThat(IdentityDb.auditOfType("membership.licence.released"))
                .anyMatch(record -> "membership_deactivated".equals(record.reason())
                        && organization.id().value().equals(record.tenantId()));

        assertThat(admin.postJson("/api/v1/members/" + other.membership() + "/reactivate", "{}").status())
                .isEqualTo(204);

        assertThat(licenceOf(admin, other.membership())).as("the licence of the default profile, because one was free")
                .isEqualTo("user");
    }

    @Test
    void aReactivatedMemberReturnsUnlicensedWhenNoLicenceIsFree() {
        Organization organization = organization(1, 1);
        TestBrowser admin = adminOf(organization);
        Member away = TestOrganizations.join(users, organization.tenant(), false);
        Member stayer = TestOrganizations.join(users, organization.tenant(), false);
        assertThat(give(admin, away.membership()).status()).isEqualTo(204);
        assertThat(admin.postJson("/api/v1/members/" + away.membership() + "/deactivate", "{}").status())
                .isEqualTo(204);
        assertThat(give(admin, stayer.membership()).status()).isEqualTo(204);

        assertThat(admin.postJson("/api/v1/members/" + away.membership() + "/reactivate", "{}").status())
                .as("reactivation never fails for lack of a licence").isEqualTo(204);

        assertThat(licenceOf(admin, away.membership())).isNull();
        assertThat(assigned(admin, "user")).isEqualTo(1);
    }

    @Test
    void aDeactivatedMemberCannotBeGivenALicence() {
        Organization organization = organization(2, 1);
        TestBrowser admin = adminOf(organization);
        Member other = TestOrganizations.join(users, organization.tenant(), false);
        assertThat(admin.postJson("/api/v1/members/" + other.membership() + "/deactivate", "{}").status())
                .isEqualTo(204);

        assertThat(give(admin, other.membership()).status()).isEqualTo(409);
    }

    // ---- licence, entitlement and permission are three separate things ----

    @Test
    void aLicenceAnEntitlementAndAPermissionEachChangeWithoutTheOthers() {
        Organization organization = organization(2, 1, "approvals");
        TestBrowser admin = adminOf(organization);
        Member member = TestOrganizations.join(users, organization.tenant(), false);
        TenantId id = organization.id();
        TestBrowser asMember = TestOrganizations.signedIn(port, organization.host(), member.person());
        UUID viewer = createProfile(admin, "profile-a", "user", "members.view");
        UUID inviter = createProfile(admin, "profile-b", "user", "members.invite");
        assertThat(setProfile(admin, member.membership(), viewer).status()).isEqualTo(204);
        assertThat(entitlements.enabled(id, "approvals")).isTrue();
        assertThat(licenceOf(admin, member.membership())).isEqualTo("user");
        assertThat(asMember.get("/api/v1/members").status()).isEqualTo(200);

        // An entitlement changes (the organization loses a feature): the licence and the permission do not.
        entitlements.override(id, "approvals", false, ActorId.SYSTEM);
        assertThat(entitlements.enabled(id, "approvals")).isFalse();
        assertThat(licenceOf(admin, member.membership())).isEqualTo("user");
        assertThat(asMember.get("/api/v1/members").status()).isEqualTo(200);
        entitlements.override(id, "approvals", null, ActorId.SYSTEM);
        assertThat(entitlements.enabled(id, "approvals")).as("back to the plan").isTrue();

        // The permission changes (another profile of the same licence type): the licence and the entitlement do not.
        assertThat(setProfile(admin, member.membership(), inviter).status()).isEqualTo(204);
        assertThat(asMember.get("/api/v1/members").status()).isEqualTo(403);
        assertThat(asMember.get("/api/v1/invitations").status()).isEqualTo(200);
        assertThat(licenceOf(admin, member.membership())).isEqualTo("user");
        assertThat(assigned(admin, "user")).isEqualTo(1);
        assertThat(entitlements.enabled(id, "approvals")).isTrue();

        // The licence is taken back: the profile gives no abilities (a licence is what lets the profile count), and the
        // feature is untouched.
        assertThat(release(admin, member.membership()).status()).isEqualTo(204);
        assertThat(asMember.get("/api/v1/invitations").status()).isEqualTo(403);
        assertThat(entitlements.enabled(id, "approvals")).isTrue();
    }

    @Test
    void anAdministratorWithoutTheirLicenceHasNoAbilitiesAndALicencedMemberWithoutAbilitiesStillCannot() {
        Organization organization = organization(2, 2);
        TestBrowser admin = adminOf(organization);
        Member secondAdministrator = TestOrganizations.join(users, organization.tenant(), true);
        Member plain = TestOrganizations.join(users, organization.tenant(), false);
        assertThat(give(admin, plain.membership()).status()).isEqualTo(204);
        TestBrowser asSecond = TestOrganizations.signedIn(port, organization.host(), secondAdministrator.person());
        assertThat(asSecond.get(POOLS).status()).isEqualTo(200);

        assertThat(release(admin, secondAdministrator.membership()).status()).isEqualTo(204);

        assertThat(licenceOf(admin, secondAdministrator.membership())).as("the administrator holds none").isNull();
        assertThat(asSecond.get(POOLS).status()).as("without the licence the profile gives nothing").isEqualTo(403);
        TestBrowser licensedMember = TestOrganizations.signedIn(port, organization.host(), plain.person());
        assertThat(licensedMember.get(POOLS).status()).as("a licence grants no permission").isEqualTo(403);
        assertThat(give(admin, secondAdministrator.membership()).status()).as("and a licence given back restores it")
                .isEqualTo(204);
        assertThat(asSecond.get(POOLS).status()).isEqualTo(200);
    }

    private void inOrganization(TenantId organization, Runnable work) {
        contexts.run(app.platform.tenant.TenantContext.of(organization), work);
    }
}
