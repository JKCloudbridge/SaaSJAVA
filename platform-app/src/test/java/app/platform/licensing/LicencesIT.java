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
 * Licences (Sprint 6, ADR-0032): an organization's administrators assign and release them, the last free licence has
 * one
 * winner under concurrency, a pool cannot be reduced below use (service and database), a deactivated member gives the
 * licence back, and a licence, an entitlement and the administrator marker are three separate things. The allowed,
 * denied and cross-organization cases of every endpoint.
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

    private Organization organization(int userLicences, int adminLicences, String... features) {
        Organization organization = TestOrganizations.create(users);
        TestPlatform.subscribe(plans, subscriptions, organization.id(), userLicences, adminLicences, features);
        return organization;
    }

    private TestBrowser adminOf(Organization organization) {
        return TestOrganizations.signedIn(port, organization.host(), organization.admin().person());
    }

    private static Response assign(TestBrowser admin, UUID membership, String type) {
        return admin.request("PUT", "/api/v1/members/" + membership + "/licence",
                "{\"licenceType\":\"" + type + "\"}");
    }

    private static Response release(TestBrowser admin, UUID membership) {
        return admin.request("DELETE", "/api/v1/members/" + membership + "/licence", null);
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
    void anAdministratorSeesTheNumbersAssignsAndReleasesALicence() {
        Organization organization = organization(2, 1);
        TestBrowser admin = adminOf(organization);
        Member other = TestOrganizations.join(users, organization.tenant(), false);

        Response pools = admin.get(POOLS);
        assertThat(pools.status()).isEqualTo(200);
        assertThat(JsonPath.<List<Integer>>read(pools.body(), "$.data[?(@.licenceType=='user')].quantity"))
                .containsExactly(2);
        assertThat(assigned(admin, "user")).isZero();
        assertThat(licenceOf(admin, other.membership())).as("a member holds none until one is assigned").isNull();

        assertThat(assign(admin, other.membership(), "user").status()).isEqualTo(204);

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
    void assigningAnotherTypeMovesTheMemberAndAnUnknownTypeIsNotFound() {
        Organization organization = organization(2, 1);
        TestBrowser admin = adminOf(organization);
        Member other = TestOrganizations.join(users, organization.tenant(), false);
        assertThat(assign(admin, other.membership(), "user").status()).isEqualTo(204);

        assertThat(assign(admin, other.membership(), "admin").status()).isEqualTo(204);

        assertThat(licenceOf(admin, other.membership())).isEqualTo("admin");
        assertThat(assigned(admin, "user")).isZero();
        assertThat(assigned(admin, "admin")).isEqualTo(1);
        assertThat(assign(admin, other.membership(), "no-such-type").status()).isEqualTo(404);
    }

    @Test
    void anOrganizationWithoutAFreeLicenceOrWithoutAPoolIsRefusedWithAConflict() {
        Organization organization = organization(1, 0);
        TestBrowser admin = adminOf(organization);
        Member first = TestOrganizations.join(users, organization.tenant(), false);
        Member second = TestOrganizations.join(users, organization.tenant(), false);
        assertThat(assign(admin, first.membership(), "user").status()).isEqualTo(204);

        Response noneFree = assign(admin, second.membership(), "user");
        Response noPool = assign(admin, second.membership(), "admin");

        assertThat(noneFree.status()).isEqualTo(409);
        assertThat(noneFree.body()).contains("No licence of this type is free.");
        assertThat(noPool.status()).as("a zero pool is an empty pool").isEqualTo(409);
    }

    // ---- who may ask, and for whom ----

    @Test
    void aMemberWhoIsNotAnAdministratorIsRefusedEverywhere() {
        Organization organization = organization(2, 1);
        Member plain = TestOrganizations.join(users, organization.tenant(), false);
        TestBrowser member = TestOrganizations.signedIn(port, organization.host(), plain.person());

        assertThat(member.get(POOLS).status()).isEqualTo(403);
        assertThat(assign(member, plain.membership(), "user").status()).isEqualTo(403);
        assertThat(release(member, plain.membership()).status()).isEqualTo(403);
        assertThat(member.postJson("/api/v1/organization/sign-out-all", "{}").status()).isEqualTo(403);
    }

    @Test
    void anAdministratorCannotReachAnotherOrganizationsMembersOrTheirLicences() {
        Organization mine = organization(2, 1);
        Organization other = organization(5, 1);
        Member theirMember = TestOrganizations.join(users, other.tenant(), false);
        TestBrowser admin = adminOf(mine);

        assertThat(assign(admin, theirMember.membership(), "user").status()).as("a foreign member is not found")
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
                "/api/v1/members/" + plain.membership() + "/licence", "{\"licenceType\":\"user\"}",
                "Authorization", bearer, "Content-Type", "application/json").status()).isEqualTo(404);
        assertThat(new TestHttp(port, "Host", organization.host()).get(POOLS).status()).as("no sign-in").isEqualTo(401);
        assertThat(onPlatformHost.get(POOLS).status()).isEqualTo(401);
    }

    // ---- concurrency and the database guards ----

    @Test
    void twoAdministratorsAssigningTheLastFreeLicenceHaveOneWinner() throws Exception {
        for (int round = 0; round < 3; round++) {
            Organization organization = organization(1, 0);
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
                    return assign(by, target, "user").status();
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
        Organization organization = organization(3, 0);
        TestBrowser admin = adminOf(organization);
        Member a = TestOrganizations.join(users, organization.tenant(), false);
        Member b = TestOrganizations.join(users, organization.tenant(), false);
        assertThat(assign(admin, a.membership(), "user").status()).isEqualTo(204);
        assertThat(assign(admin, b.membership(), "user").status()).isEqualTo(204);

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
        Organization organization = organization(2, 0);
        TestBrowser admin = adminOf(organization);
        Member other = TestOrganizations.join(users, organization.tenant(), false);
        assertThat(assign(admin, other.membership(), "user").status()).isEqualTo(204);
        assertThat(assigned(admin, "user")).isEqualTo(1);

        assertThat(admin.postJson("/api/v1/members/" + other.membership() + "/deactivate", "{}").status())
                .isEqualTo(204);

        assertThat(assigned(admin, "user")).as("the licence went back to the pool").isZero();
        assertThat(IdentityDb.auditOfType("membership.licence.released"))
                .anyMatch(record -> "membership_deactivated".equals(record.reason())
                        && organization.id().value().equals(record.tenantId()));

        assertThat(admin.postJson("/api/v1/members/" + other.membership() + "/reactivate", "{}").status())
                .isEqualTo(204);

        assertThat(licenceOf(admin, other.membership())).as("the default licence, because one was free")
                .isEqualTo("user");
    }

    @Test
    void aReactivatedMemberReturnsUnlicensedWhenNoLicenceIsFree() {
        Organization organization = organization(1, 0);
        TestBrowser admin = adminOf(organization);
        Member away = TestOrganizations.join(users, organization.tenant(), false);
        Member stayer = TestOrganizations.join(users, organization.tenant(), false);
        assertThat(assign(admin, away.membership(), "user").status()).isEqualTo(204);
        assertThat(admin.postJson("/api/v1/members/" + away.membership() + "/deactivate", "{}").status())
                .isEqualTo(204);
        assertThat(assign(admin, stayer.membership(), "user").status()).isEqualTo(204);

        assertThat(admin.postJson("/api/v1/members/" + away.membership() + "/reactivate", "{}").status())
                .as("reactivation never fails for lack of a licence").isEqualTo(204);

        assertThat(licenceOf(admin, away.membership())).isNull();
        assertThat(assigned(admin, "user")).isEqualTo(1);
    }

    @Test
    void aDeactivatedMemberCannotBeGivenALicence() {
        Organization organization = organization(2, 0);
        TestBrowser admin = adminOf(organization);
        Member other = TestOrganizations.join(users, organization.tenant(), false);
        assertThat(admin.postJson("/api/v1/members/" + other.membership() + "/deactivate", "{}").status())
                .isEqualTo(204);

        assertThat(assign(admin, other.membership(), "user").status()).isEqualTo(409);
    }

    // ---- licence, entitlement and permission are three separate things ----

    @Test
    void aLicenceAnEntitlementAndTheAdministratorMarkerEachChangeWithoutTheOthers() {
        Organization organization = organization(2, 1, "approvals");
        TestBrowser admin = adminOf(organization);
        Member member = TestOrganizations.join(users, organization.tenant(), false);
        TenantId id = organization.id();
        assertThat(entitlements.enabled(id, "approvals")).isTrue();
        assertThat(licenceOf(admin, member.membership())).isNull();

        // A licence changes: the entitlement and the marker do not.
        assertThat(assign(admin, member.membership(), "user").status()).isEqualTo(204);
        assertThat(entitlements.enabled(id, "approvals")).isTrue();
        assertThat(administratorMarker(admin, member.membership())).isFalse();

        // An entitlement changes (the organization loses a feature): the licence and the marker do not.
        entitlements.override(id, "approvals", false, ActorId.SYSTEM);
        assertThat(entitlements.enabled(id, "approvals")).isFalse();
        assertThat(licenceOf(admin, member.membership())).isEqualTo("user");
        assertThat(administratorMarker(admin, member.membership())).isFalse();
        entitlements.override(id, "approvals", null, ActorId.SYSTEM);
        assertThat(entitlements.enabled(id, "approvals")).as("back to the plan").isTrue();

        // The marker (the permission of this sprint) changes: the licence and the entitlement do not.
        assertThat(admin.request("PUT", "/api/v1/members/" + member.membership() + "/administrator",
                "{\"administrator\":true}").status()).isEqualTo(204);
        assertThat(administratorMarker(admin, member.membership())).isTrue();
        assertThat(licenceOf(admin, member.membership())).isEqualTo("user");
        assertThat(entitlements.enabled(id, "approvals")).isTrue();

        // And the other way round: releasing the licence takes no permission away.
        assertThat(release(admin, member.membership()).status()).isEqualTo(204);
        assertThat(administratorMarker(admin, member.membership())).isTrue();
    }

    @Test
    void anAdministratorWithoutALicenceCanStillAdministerAndALicencedMemberStillCannot() {
        Organization organization = organization(2, 1);
        TestBrowser admin = adminOf(organization);
        Member plain = TestOrganizations.join(users, organization.tenant(), false);
        assertThat(assign(admin, plain.membership(), "user").status()).isEqualTo(204);

        assertThat(licenceOf(admin, organization.admin().membership())).as("the administrator holds none").isNull();
        assertThat(admin.get(POOLS).status()).as("no licence is needed to administer").isEqualTo(200);
        TestBrowser licensedMember = TestOrganizations.signedIn(port, organization.host(), plain.person());
        assertThat(licensedMember.get(POOLS).status()).as("a licence grants no permission").isEqualTo(403);
    }

    private static boolean administratorMarker(TestBrowser admin, UUID membership) {
        List<Boolean> value = JsonPath.read(admin.get("/api/v1/members").body(),
                "$.data[?(@.id=='" + membership + "')].administrator");
        return value.get(0);
    }

    private void inOrganization(TenantId organization, Runnable work) {
        contexts.run(app.platform.tenant.TenantContext.of(organization), work);
    }

    @Autowired
    private app.platform.tenant.TenantContexts contexts;
}
