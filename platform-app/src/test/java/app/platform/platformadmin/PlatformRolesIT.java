package app.platform.platformadmin;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.identity.PlatformRole;
import app.platform.identity.Users;
import app.platform.testsupport.IdentityDb;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations;
import app.platform.testsupport.TestOrganizations.Organization;
import app.platform.testsupport.TestPlatform;
import app.platform.testsupport.TestSignIn;
import app.platform.testsupport.TestUsers;
import app.platform.testsupport.TestUsers.TestUser;
import app.platform.testsupport.tenancy.TenantFixtures;
import app.platform.testsupport.tenancy.TenantFixtures.TestTenant;
import com.jayway.jsonpath.JsonPath;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Platform roles (Sprint 6, ADR-0030): what each role may do against every platform endpoint, that an organization
 * administrator, an ordinary member and a platform person inside an organization have no platform ability and a
 * platform role gives no organization authority, that platform endpoints answer on the platform host only, that
 * granting and revoking are audited and take effect at once, that the last platform administrator stays (also under
 * concurrency), and the protections of platform accounts: a loud sign-in record, a short session and a lock that comes
 * sooner.
 */
@PlatformIntegrationTest
class PlatformRolesIT {

    private static final Set<PlatformRole> ADMIN = EnumSet.of(PlatformRole.PLATFORM_ADMIN);
    private static final Set<PlatformRole> ADMIN_SUPPORT =
            EnumSet.of(PlatformRole.PLATFORM_ADMIN, PlatformRole.PLATFORM_SUPPORT);
    private static final Set<PlatformRole> ADMIN_BILLING =
            EnumSet.of(PlatformRole.PLATFORM_ADMIN, PlatformRole.PLATFORM_BILLING);
    private static final Set<PlatformRole> ALL = EnumSet.allOf(PlatformRole.class);

    /** One platform endpoint, what to send and which roles may use it. */
    private record Endpoint(String name, String method, String path, String body, Set<PlatformRole> allowed) {
    }

    @LocalServerPort
    private int port;

    @Autowired
    private Users users;

    private List<Endpoint> endpoints(UUID organization, String account) {
        String o = "/api/v1/platform/organizations/" + organization;
        String reason = "{\"reason\":\"test\"}";
        String key = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        return List.of(
                new Endpoint("list organizations", "GET", "/api/v1/platform/organizations", null, ALL),
                new Endpoint("one organization", "GET", o, null, ALL),
                new Endpoint("provision", "POST", "/api/v1/platform/organizations",
                        "{\"displayName\":\"Org " + key + "\",\"slug\":\"org-" + key
                                + "\",\"planKey\":\"trial\",\"email\":\"person-" + key + "@example.test\"}", ADMIN),
                new Endpoint("suspend", "POST", o + "/suspend", "{\"reason\":\"test\"}", ADMIN),
                new Endpoint("reinstate", "POST", o + "/reinstate", "{\"reason\":\"test\"}", ADMIN),
                new Endpoint("deactivate", "POST", o + "/deactivate",
                        "{\"reason\":\"test\",\"confirm\":\"not-the-name\"}", ADMIN),
                new Endpoint("invite first administrator", "POST", o + "/first-administrator",
                        "{\"email\":\"person-" + key + "@example.test\"}", ADMIN),
                new Endpoint("resend first administrator", "POST", o + "/first-administrator/resend", null,
                        ADMIN_SUPPORT),
                new Endpoint("change subscription", "PUT", o + "/subscription",
                        "{\"planKey\":\"trial\",\"reason\":\"test\"}", ADMIN_BILLING),
                new Endpoint("set pool", "PUT", o + "/pools/user", "{\"quantity\":3,\"reason\":\"test\"}", ADMIN),
                new Endpoint("set entitlement", "PUT", o + "/entitlements/approvals",
                        "{\"enabled\":true,\"reason\":\"test\"}", ADMIN),
                new Endpoint("sign out an organization", "POST", o + "/sign-out-all", reason, ADMIN_SUPPORT),
                new Endpoint("request support access", "POST", o + "/support-access",
                        "{\"reason\":\"test\",\"minutes\":30}", ADMIN_SUPPORT),
                new Endpoint("list support access", "GET", o + "/support-access", null, ADMIN_SUPPORT),
                new Endpoint("cancel support access", "POST", o + "/support-access/" + UUID.randomUUID() + "/cancel",
                        null, ADMIN_SUPPORT),
                new Endpoint("list platform people", "GET", "/api/v1/platform/people", null, ADMIN),
                new Endpoint("grant a role", "POST", "/api/v1/platform/people",
                        "{\"email\":\"" + account + "\",\"role\":\"PLATFORM_BILLING\"}", ADMIN),
                new Endpoint("revoke a role", "DELETE", "/api/v1/platform/people/" + UUID.randomUUID(), null, ADMIN),
                new Endpoint("list plans", "GET", "/api/v1/platform/plans", null, ALL),
                new Endpoint("save a plan", "PUT", "/api/v1/platform/plans/matrix-" + key,
                        "{\"name\":\"Matrix\",\"licences\":{\"user\":1},\"features\":[]}", ADMIN_BILLING),
                new Endpoint("list licence types", "GET", "/api/v1/platform/licence-types", null, ALL),
                new Endpoint("add a licence type", "POST", "/api/v1/platform/licence-types",
                        "{\"key\":\"lt-" + key + "\",\"name\":\"Matrix\"}", ADMIN_BILLING),
                new Endpoint("list features", "GET", "/api/v1/platform/features", null, ALL),
                new Endpoint("add a feature", "POST", "/api/v1/platform/features",
                        "{\"key\":\"ft-" + key + "\",\"name\":\"Matrix\"}", ADMIN_BILLING),
                new Endpoint("look up sessions", "POST", "/api/v1/platform/sessions/lookup",
                        "{\"email\":\"" + account + "\",\"reason\":\"test\"}", ADMIN_SUPPORT),
                new Endpoint("sign a person out", "POST", "/api/v1/platform/sessions/sign-out",
                        "{\"email\":\"" + account + "\",\"reason\":\"test\"}", ADMIN_SUPPORT));
    }

    // ---- what each role may do ----

    @Test
    void eachPlatformRoleCanUseOnlyItsOwnEndpointsAndEveryoneElseIsForbidden() {
        TestTenant tenant = TenantFixtures.createActiveTenant();
        TestUser account = TestUsers.create(users);
        TestBrowser admin = TestPlatform.signedIn(port, TestPlatform.person(users, PlatformRole.PLATFORM_ADMIN));
        TestBrowser support = TestPlatform.signedIn(port, TestPlatform.person(users, PlatformRole.PLATFORM_SUPPORT));
        TestBrowser billing = TestPlatform.signedIn(port, TestPlatform.person(users, PlatformRole.PLATFORM_BILLING));
        TestBrowser ordinary = TestOrganizations.signedIn(port, TestSignIn.PLATFORM_HOST, TestUsers.create(users));
        Organization own = TestOrganizations.create(users);
        // An administrator of an organization signs in on the platform host like anybody else.
        TestBrowser organizationAdmin = TestOrganizations.signedIn(port, TestSignIn.PLATFORM_HOST,
                own.admin().person());

        List<String> wrong = new ArrayList<>();
        for (Endpoint endpoint : endpoints(tenant.id().value(), account.email())) {
            for (Object[] caller : new Object[][] {
                {"admin", admin, PlatformRole.PLATFORM_ADMIN}, {"support", support, PlatformRole.PLATFORM_SUPPORT},
                {"billing", billing, PlatformRole.PLATFORM_BILLING}, {"ordinary person", ordinary, null},
                {"organization administrator", organizationAdmin, null}}) {
                TestBrowser browser = (TestBrowser) caller[1];
                PlatformRole role = (PlatformRole) caller[2];
                int status = browser.request(endpoint.method(), endpoint.path(), endpoint.body()).status();
                boolean mayUse = role != null && endpoint.allowed().contains(role);
                if (mayUse && (status == 403 || status == 401)) {
                    wrong.add(caller[0] + " was refused " + endpoint.name() + " (" + status + ")");
                }
                if (!mayUse && status != 403) {
                    wrong.add(caller[0] + " reached " + endpoint.name() + " (" + status + ")");
                }
            }
        }
        assertThat(wrong).as("role against endpoint").isEmpty();
    }

    @Test
    void everyPlatformEndpointNeedsASignIn() {
        TestTenant tenant = TenantFixtures.createActiveTenant();
        TestBrowser nobody = new TestBrowser(port, TestSignIn.PLATFORM_HOST);
        List<String> open = new ArrayList<>();
        for (Endpoint endpoint : endpoints(tenant.id().value(), "person-a@example.test")) {
            int status = nobody.request(endpoint.method(), endpoint.path(), endpoint.body()).status();
            if (status != 401) {
                open.add(endpoint.name() + " answered " + status);
            }
        }
        assertThat(open).isEmpty();
    }

    // ---- the host decides which half of the API exists ----

    @Test
    void platformEndpointsAreNotFoundOnAnOrganizationHostEvenForAPlatformAdministratorWhoIsAMember() {
        Organization organization = TestOrganizations.create(users);
        TestUser person = TestUsers.create(users);
        TestPlatform.grant(person.user().id(), PlatformRole.PLATFORM_ADMIN);
        TestOrganizations.join(organization.tenant(), person, false);
        TestBrowser inside = TestOrganizations.signedIn(port, organization.host(), person);
        TestTenant other = TenantFixtures.createActiveTenant();

        List<String> reached = new ArrayList<>();
        for (Endpoint endpoint : endpoints(other.id().value(), "person-a@example.test")) {
            int status = inside.request(endpoint.method(), endpoint.path(), endpoint.body()).status();
            if (status != 404) {
                reached.add(endpoint.name() + " answered " + status);
            }
        }
        assertThat(reached).as("the console does not exist on an organization host").isEmpty();
        assertThat(JsonPath.<List<String>>read(inside.get("/api/v1/auth/me").body(), "$.data.platformRoles"))
                .as("on an organization host the person is an ordinary member").isEmpty();
    }

    @Test
    void aPlatformRoleGivesNoAuthorityInsideAnOrganization() {
        Organization organization = TestOrganizations.create(users);
        TestUser person = TestUsers.create(users);
        TestPlatform.grant(person.user().id(), PlatformRole.PLATFORM_ADMIN);
        UUID membership = TestOrganizations.join(organization.tenant(), person, false);
        TestBrowser inside = TestOrganizations.signedIn(port, organization.host(), person);
        TestBrowser platform = TestPlatform.signedIn(port, person);

        // An ordinary member there: every administrative endpoint of the organization refuses.
        assertThat(inside.get("/api/v1/members").status()).isEqualTo(403);
        assertThat(inside.get("/api/v1/invitations").status()).isEqualTo(403);
        assertThat(inside.get("/api/v1/licences").status()).isEqualTo(403);
        assertThat(inside.get("/api/v1/support-access").status()).isEqualTo(403);
        assertThat(inside.postJson("/api/v1/members/" + membership + "/deactivate", "{}").status()).isEqualTo(403);
        // And on the platform host there is no organization for those endpoints to be about.
        assertThat(platform.get("/api/v1/members").status()).isEqualTo(404);
        assertThat(platform.get("/api/v1/licences").status()).isEqualTo(404);
        assertThat(platform.get("/api/v1/support-access").status()).isEqualTo(404);
        // The roles are visible to the console on the platform host only.
        assertThat(JsonPath.<List<String>>read(platform.get("/api/v1/auth/me").body(), "$.data.platformRoles"))
                .containsExactly("PLATFORM_ADMIN");
    }

    @Test
    void aPlatformPersonWhoIsNotAMemberCannotSignInToAnOrganizationLikeAnyOtherNonMember() {
        Organization organization = TestOrganizations.create(users);
        TestUser person = TestPlatform.person(users, PlatformRole.PLATFORM_ADMIN);

        Response refused = new TestBrowser(port, organization.host()).signInPassword(person.email(),
                person.password());

        assertThat(refused.status()).as("a platform role is not a membership").isEqualTo(401);
    }

    @Test
    void aForgedTenantHeaderOnAPlatformEndpointChangesNothing() {
        Organization organization = TestOrganizations.create(users);
        TestUser person = TestPlatform.person(users, PlatformRole.PLATFORM_ADMIN);
        String token = new TestBrowser(port, TestSignIn.PLATFORM_HOST).signIn(person.email(), person.password())
                .bearer();

        Response plain = new TestHttp(port, "Host", TestSignIn.PLATFORM_HOST).get("/api/v1/platform/organizations",
                "Authorization", token);
        Response forged = new TestHttp(port, "Host", TestSignIn.PLATFORM_HOST).get("/api/v1/platform/organizations",
                "Authorization", token, "X-Tenant-Id", organization.id().toString(), "X-Forwarded-Host",
                organization.host());

        assertThat(plain.status()).isEqualTo(200);
        assertThat(forged.status()).as("the console is still the console").isEqualTo(200);
        assertThat(JsonPath.<Integer>read(forged.body(), "$.data.length()"))
                .isEqualTo(JsonPath.<Integer>read(plain.body(), "$.data.length()"));
    }

    @Test
    void aForgedTenantHeaderNeverMovesAWriteToAnotherOrganization() throws SQLException {
        Organization target = TestOrganizations.create(users);
        Organization decoy = TestOrganizations.create(users);
        TestUser person = TestPlatform.person(users, PlatformRole.PLATFORM_ADMIN);
        String token = new TestBrowser(port, TestSignIn.PLATFORM_HOST).signIn(person.email(), person.password())
                .bearer();
        String path = "/api/v1/platform/organizations/" + target.id().value() + "/pools/user";

        Response forged = new TestHttp(port, "Host", TestSignIn.PLATFORM_HOST).request("PUT", path,
                "{\"quantity\":9,\"reason\":\"test\"}", "Authorization", token, "Content-Type", "application/json",
                "X-Tenant-Id", decoy.id().toString(), "X-Forwarded-Host", decoy.host());

        assertThat(forged.status()).isEqualTo(200);
        assertThat(IdentityDb.value(Integer.class, "select p.quantity from licence_pool p join licence_type t "
                + "on t.id = p.licence_type_id where p.tenant_id = ? and t.key = 'user'",
                target.id().value())).as("the organization in the path changed").isEqualTo(9);
        assertThat(IdentityDb.value(Long.class, "select count(*) from licence_pool p join licence_type t "
                + "on t.id = p.licence_type_id where p.tenant_id = ? and t.key = 'user'",
                decoy.id().value())).as("the organization in the header did not").isZero();
        assertThat(IdentityDb.auditOfType("platform.licence_pool.changed")).anyMatch(record ->
                target.id().value().equals(record.tenantId()));
    }

    // ---- granting and revoking ----

    @Test
    void anAdministratorGrantsAndRevokesRolesAndEveryChangeIsAuditedAndTakesEffectAtOnce() throws SQLException {
        TestUser admin = TestPlatform.person(users, PlatformRole.PLATFORM_ADMIN);
        TestBrowser console = TestPlatform.signedIn(port, admin);
        TestUser colleague = TestUsers.create(users);
        TestBrowser colleagueBrowser = TestPlatform.signedIn(port, colleague);
        assertThat(colleagueBrowser.get("/api/v1/platform/plans").status()).isEqualTo(403);

        Response granted = console.postJson("/api/v1/platform/people",
                "{\"email\":\"" + colleague.email() + "\",\"role\":\"PLATFORM_SUPPORT\"}");

        assertThat(granted.status()).isEqualTo(201);
        String assignment = JsonPath.read(granted.body(), "$.data.id");
        assertThat(colleagueBrowser.get("/api/v1/platform/plans").status()).as("at once, no new sign-in")
                .isEqualTo(200);
        assertThat(console.postJson("/api/v1/platform/people",
                "{\"email\":\"" + colleague.email() + "\",\"role\":\"PLATFORM_SUPPORT\"}").status())
                .as("a role held twice").isEqualTo(409);
        assertThat(console.postJson("/api/v1/platform/people",
                "{\"email\":\"nobody-" + UUID.randomUUID() + "@example.test\",\"role\":\"PLATFORM_SUPPORT\"}").status())
                .isEqualTo(400);
        assertThat(console.postJson("/api/v1/platform/people",
                "{\"email\":\"" + colleague.email() + "\",\"role\":\"NOT_A_ROLE\"}").status()).isEqualTo(400);
        assertThat(IdentityDb.auditOfType("platform.role.granted")).anyMatch(record ->
                admin.user().id().equals(record.userId()) && record.attributes().contains("PLATFORM_SUPPORT")
                        && record.attributes().contains(colleague.user().id().toString()));

        assertThat(console.request("DELETE", "/api/v1/platform/people/" + assignment, null).status())
                .isEqualTo(204);

        assertThat(colleagueBrowser.get("/api/v1/platform/plans").status()).as("revoked at once").isEqualTo(403);
        assertThat(IdentityDb.auditOfType("platform.role.revoked")).anyMatch(record ->
                admin.user().id().equals(record.userId()) && record.attributes().contains(colleague.user().id()
                        .toString()));
        assertThat(IdentityDb.auditOf(colleague.user().id())).as("the refusals are recorded too")
                .anyMatch(record -> "platform.action.refused".equals(record.type()));
    }

    // ---- the last platform administrator stays ----

    @Test
    void theLastPlatformAdministratorCannotBeRemovedAndTwoSteppingDownAtOnceLeaveOne() throws Exception {
        List<UUID> others = liveAdministratorAssignments();
        try {
            hide(others);
            TestUser first = TestPlatform.person(users, PlatformRole.PLATFORM_ADMIN);
            TestUser second = TestPlatform.person(users, PlatformRole.PLATFORM_ADMIN);
            TestBrowser firstConsole = TestPlatform.signedIn(port, first);
            TestBrowser secondConsole = TestPlatform.signedIn(port, second);
            List<UUID> both = liveAdministratorAssignments();
            assertThat(both).hasSize(2);

            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch start = new CountDownLatch(1);
            Future<Integer> a = pool.submit(() -> {
                start.await();
                return firstConsole.request("DELETE", "/api/v1/platform/people/" + both.get(0), null).status();
            });
            Future<Integer> b = pool.submit(() -> {
                start.await();
                return secondConsole.request("DELETE", "/api/v1/platform/people/" + both.get(1), null).status();
            });
            start.countDown();
            List<Integer> statuses = List.of(a.get(), b.get());
            pool.shutdown();

            assertThat(statuses).as("exactly one step-down wins").containsExactlyInAnyOrder(204, 409);
            assertThat(liveAdministratorAssignments()).as("one administrator is left").hasSize(1);
            UUID last = liveAdministratorAssignments().get(0);
            TestBrowser remaining = last.equals(both.get(0)) ? firstConsole : secondConsole;
            Response refused = remaining.request("DELETE", "/api/v1/platform/people/" + last, null);
            assertThat(refused.status()).isEqualTo(409);
            assertThat(refused.body()).contains("The last platform administrator cannot be removed");
            assertThat(IdentityDb.value(Long.class, "select count(*) from platform_role_assignment "
                    + "where id = ? and deleted_at is null", last)).isEqualTo(1);
            // The database refuses it as well, whoever asks (here the owner, with the console out of the way).
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> IdentityDb.execute(
                    "update platform_role_assignment set deleted_at = now(), deleted_by = created_by, "
                            + "version = version + 1 where id = ?", last))
                    .hasMessageContaining("last platform administrator");
        } finally {
            restore(others);
        }
    }

    private static List<UUID> liveAdministratorAssignments() throws SQLException {
        return IdentityDb.strings("select a.id::text from platform_role_assignment a join platform_user u "
                        + "on u.id = a.user_id where a.role = 'PLATFORM_ADMIN' and a.deleted_at is null "
                        + "and u.status = 'ACTIVE' order by a.created_at, a.id").stream().map(UUID::fromString)
                .toList();
    }

    /** Takes the other tests' administrators out of the way for one test (the guard is switched off to do it). */
    private static void hide(List<UUID> ids) throws SQLException {
        for (UUID id : ids) {
            IdentityDb.executeWithoutTriggers("update platform_role_assignment set deleted_at = now(), "
                    + "deleted_by = created_by, version = version + 1 where id = ?", id);
        }
    }

    private static void restore(List<UUID> ids) throws SQLException {
        for (UUID id : ids) {
            IdentityDb.executeWithoutTriggers("update platform_role_assignment set deleted_at = null, "
                    + "deleted_by = null, version = version + 1 where id = ?", id);
        }
    }

    // ---- the protections of platform accounts ----

    @Test
    void everyPlatformSignInIsRecordedLoudlyAndAnOrdinarySignInIsNot() {
        TestUser platformPerson = TestPlatform.person(users, PlatformRole.PLATFORM_SUPPORT);
        TestUser ordinary = TestUsers.create(users);

        TestPlatform.signedIn(port, platformPerson);
        TestPlatform.signedIn(port, ordinary);

        assertThat(IdentityDb.auditOf(platformPerson.user().id())).anyMatch(record ->
                "platform.sign_in.succeeded".equals(record.type()) && record.attributes().contains("PLATFORM_SUPPORT"));
        assertThat(IdentityDb.auditOf(ordinary.user().id())).noneMatch(record ->
                "platform.sign_in.succeeded".equals(record.type()));
    }

    @Test
    void aPlatformSessionEndsAfterItsShortLifeWhileAnOrdinarySessionOfTheSameAgeLives() throws SQLException {
        TestUser platformPerson = TestPlatform.person(users, PlatformRole.PLATFORM_SUPPORT);
        TestUser ordinary = TestUsers.create(users);
        TestBrowser platformBrowser = TestPlatform.signedIn(port, platformPerson);
        TestBrowser ordinaryBrowser = TestOrganizations.signedIn(port, TestSignIn.PLATFORM_HOST, ordinary);
        assertThat(platformBrowser.get("/api/v1/platform/plans").status()).isEqualTo(200);

        // Both sign-ins began five hours ago.
        IdentityDb.executeWithoutTriggers("update oauth2_authorization set created_at = now() - interval '5 hours' "
                + "where user_id in (?, ?)", platformPerson.user().id(), ordinary.user().id());

        assertThat(platformBrowser.get("/api/v1/auth/me").status()).as("a platform role: sign in again")
                .isEqualTo(401);
        assertThat(ordinaryBrowser.get("/api/v1/auth/me").status()).as("an ordinary person is unaffected")
                .isEqualTo(200);
    }

    @Test
    void aPlatformAccountLocksAfterThreeFailuresWhileAnOrdinaryAccountNeedsFive() {
        TestUser platformPerson = TestPlatform.person(users, PlatformRole.PLATFORM_ADMIN);
        TestUser ordinary = TestUsers.create(users);

        for (int i = 0; i < 3; i++) {
            new TestBrowser(port, TestSignIn.PLATFORM_HOST).fromSource("192.0.2." + (10 + i))
                    .signInPassword(platformPerson.email(), "wrong password " + i);
            new TestBrowser(port, TestSignIn.PLATFORM_HOST).fromSource("192.0.2." + (20 + i))
                    .signInPassword(ordinary.email(), "wrong password " + i);
        }

        assertThat(new TestBrowser(port, TestSignIn.PLATFORM_HOST).fromSource("192.0.2.40")
                .signInPassword(platformPerson.email(), platformPerson.password()).status())
                .as("locked after three failures").isEqualTo(401);
        assertThat(new TestBrowser(port, TestSignIn.PLATFORM_HOST).fromSource("192.0.2.41")
                .signInPassword(ordinary.email(), ordinary.password()).status())
                .as("not yet locked after three").isEqualTo(204);
    }
}
