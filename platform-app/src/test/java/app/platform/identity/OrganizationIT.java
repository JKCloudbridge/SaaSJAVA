package app.platform.identity;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.sharedkernel.TenantId;
import app.platform.tenant.TenantSlug;
import app.platform.tenant.TenantStatus;
import app.platform.tenant.Tenants;
import app.platform.testsupport.IdentityDb;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestSignIn;
import app.platform.testsupport.TestUsers;
import app.platform.testsupport.TestUsers.TestUser;
import app.platform.testsupport.tenancy.TenantFixtures;
import com.jayway.jsonpath.JsonPath;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * A signed-in person founds an organization (Sprint 4, ADR-0025): what is created, who becomes what, that the tenant
 * can never be chosen or taken over through this endpoint, the limits, and the allowed, denied and cross-tenant
 * authorization cases of the endpoint.
 */
@PlatformIntegrationTest
class OrganizationIT {

    @LocalServerPort
    private int port;

    @Autowired
    private Users users;

    @Autowired
    private Tenants tenants;

    private static String newSlug() {
        return "org-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private TestBrowser signedInOnThePlatformHost(TestUser user) {
        TestBrowser browser = new TestBrowser(port, TestSignIn.PLATFORM_HOST);
        browser.signIn(user.email(), user.password());
        return browser;
    }

    private static Response found(TestBrowser browser, String name, String slug) {
        return browser.postJson("/api/v1/organizations",
                "{\"displayName\":\"" + name + "\",\"slug\":\"" + slug + "\"}");
    }

    private TenantId tenantOf(String slug) {
        return tenants.findBySlug(new TenantSlug(slug)).orElseThrow().id();
    }

    private long membershipsOf(TenantId tenant, UUID userId) throws SQLException {
        return TenantFixtures.asTenant(tenant, connection -> TenantFixtures.count(connection,
                "select count(*) from membership where user_id = ?", userId));
    }

    @Test
    void aPersonFoundsAnOrganizationAndBecomesItsFoundingAdministrator() throws SQLException {
        TestUser person = TestUsers.create(users);
        TestBrowser browser = signedInOnThePlatformHost(person);
        String slug = newSlug();

        Response created = found(browser, "Organization A", slug);

        assertThat(created.status()).isEqualTo(201);
        assertThat(JsonPath.<String>read(created.body(), "$.data.slug")).isEqualTo(slug);
        assertThat(JsonPath.<String>read(created.body(), "$.data.displayName")).isEqualTo("Organization A");
        assertThat(JsonPath.<String>read(created.body(), "$.data.host")).isEqualTo(slug + "."
                + TestSignIn.PLATFORM_HOST);
        TenantId tenant = tenantOf(slug);
        assertThat(tenants.findById(tenant).orElseThrow().status()).isEqualTo(TenantStatus.ACTIVE);
        UUID userId = person.user().id();
        assertThat(membershipsOf(tenant, userId)).isEqualTo(1);
        assertThat(IdentityDb.value(Boolean.class, "select founding_administrator from membership where user_id = ? "
                + "and tenant_id = ?", userId, tenant.value())).isTrue();
        assertThat(IdentityDb.auditOfType("tenant.organization.founded"))
                .anyMatch(record -> userId.equals(record.userId()) && tenant.value().equals(record.tenantId()));
        // The person can open the new organization's address and sign in there.
        TestBrowser inside = new TestBrowser(port, slug + "." + TestSignIn.PLATFORM_HOST);
        assertThat(inside.get("/api/v1/tenant/current").status()).isEqualTo(200);
        inside.signIn(person.email(), person.password());
        assertThat(inside.get("/api/v1/auth/me").status()).isEqualTo(200);
    }

    @Test
    void theMembershipBelongsToTheNewOrganizationAloneAndAnotherTenantCannotSeeIt() throws SQLException {
        TestUser person = TestUsers.create(users);
        String slug = newSlug();
        found(signedInOnThePlatformHost(person), "Organization A", slug);
        TenantId other = TenantFixtures.createActiveTenant().id();

        assertThat(membershipsOf(tenantOf(slug), person.user().id())).isEqualTo(1);
        assertThat(membershipsOf(other, person.user().id())).isZero();
    }

    @Test
    void theTenantCannotBeChosenOrTakenOverThroughTheRequest() throws SQLException {
        TestUser owner = TestUsers.create(users);
        String existing = newSlug();
        found(signedInOnThePlatformHost(owner), "Organization A", existing);
        TenantId existingTenant = tenantOf(existing);
        TestUser intruder = TestUsers.create(users);
        TestBrowser browser = signedInOnThePlatformHost(intruder);

        Response sameSlug = found(browser, "Organization B", existing);
        // A request that names a tenant by any other means gets a new organization of its own and nothing else.
        String own = newSlug();
        Response hint = browser.postJson("/api/v1/organizations", "{\"displayName\":\"Organization C\",\"slug\":\""
                + own + "\",\"tenantId\":\"" + existingTenant.value() + "\"}",
                "X-Tenant-Id", existingTenant.toString());

        assertThat(sameSlug.status()).isEqualTo(400);
        assertThat(sameSlug.body()).contains("\"slug\"");
        assertThat(hint.status()).isEqualTo(201);
        assertThat(membershipsOf(existingTenant, intruder.user().id())).as("no seat in the existing organization")
                .isZero();
        assertThat(membershipsOf(existingTenant, owner.user().id())).isEqualTo(1);
        assertThat(membershipsOf(tenantOf(own), intruder.user().id())).isEqualTo(1);
    }

    @Test
    void aNameThatIsInvalidReservedOrTakenIsRefusedOnTheSlugField() {
        TestBrowser browser = signedInOnThePlatformHost(TestUsers.create(users));
        TenantFixtures.TestTenant taken = TenantFixtures.createActiveTenant();

        for (String slug : List.of("ab", "Upper-Case", "has space", "-leading", "admin", "www", taken.slug())) {
            Response refused = found(browser, "Organization A", slug);
            assertThat(refused.status()).as(slug).isEqualTo(400);
            assertThat(refused.body()).as(slug).contains("\"slug\"");
        }
        assertThat(found(browser, "  padded  ", newSlug()).status()).isEqualTo(400);
        assertThat(found(browser, "Organization A", newSlug()).status()).isEqualTo(201);
    }

    @Test
    void aPersonMayFoundAtMostThreeOrganizations() {
        TestBrowser browser = signedInOnThePlatformHost(TestUsers.create(users));
        for (int i = 0; i < 3; i++) {
            assertThat(found(browser, "Organization " + i, newSlug()).status()).isEqualTo(201);
        }

        Response fourth = found(browser, "Organization 4", newSlug());

        assertThat(fourth.status()).isEqualTo(403);
        assertThat(JsonPath.<String>read(fourth.body(), "$.error.code")).isEqualTo("FORBIDDEN");
        assertThat(found(signedInOnThePlatformHost(TestUsers.create(users)), "Organization A", newSlug()).status())
                .as("another person is not affected").isEqualTo(201);
    }

    @Test
    void twoPeopleAskingForOneNameAtOnceHaveOneWinner() throws Exception {
        String slug = newSlug();
        List<TestBrowser> browsers = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            browsers.add(signedInOnThePlatformHost(TestUsers.create(users)));
        }
        ExecutorService pool = Executors.newFixedThreadPool(6);
        List<Integer> statuses = new ArrayList<>();
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (TestBrowser browser : browsers) {
                results.add(pool.submit(() -> found(browser, "Organization A", slug).status()));
            }
            for (Future<Integer> result : results) {
                statuses.add(result.get());
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(statuses.stream().filter(status -> status == 201)).hasSize(1);
        assertThat(statuses.stream().filter(status -> status == 400)).hasSize(5);
        assertThat(IdentityDb.value(Long.class, "select count(*) from tenant where slug = ?", slug)).isEqualTo(1L);
        assertThat(IdentityDb.value(Long.class, "select count(*) from membership m join tenant t on t.id = m.tenant_id "
                + "where t.slug = ?", slug)).isEqualTo(1L);
    }

    // ---- authorization: allowed above; denied and cross-tenant here ----

    @Test
    void anonymousCallersAreRefused() {
        Response refused = new TestBrowser(port, TestSignIn.PLATFORM_HOST).postJson("/api/v1/organizations",
                "{\"displayName\":\"Organization A\",\"slug\":\"" + newSlug() + "\"}");

        assertThat(refused.status()).isEqualTo(401);
    }

    @Test
    void theEndpointDoesNotExistOnAnOrganizationHost() {
        TestUser person = TestUsers.create(users);
        TenantFixtures.TestTenant organization = TenantFixtures.createActiveTenant();
        TestBrowser inside = new TestBrowser(port, organization.host());
        inside.signIn(person.email(), person.password());

        Response refused = found(inside, "Organization A", newSlug());

        assertThat(refused.status()).isEqualTo(404);
    }

    @Test
    void aTokenFromAnotherHostIsRefused() {
        TestUser person = TestUsers.create(users);
        TenantFixtures.TestTenant organization = TenantFixtures.createActiveTenant();
        String bearer = new TestBrowser(port, organization.host()).signIn(person.email(), person.password()).bearer();

        Response refused = new TestHttp(port).request("POST", "/api/v1/organizations",
                "{\"displayName\":\"Organization A\",\"slug\":\"" + newSlug() + "\"}",
                "Host", TestSignIn.PLATFORM_HOST, "Authorization", bearer, "Content-Type", "application/json");

        assertThat(refused.status()).isEqualTo(401);
    }
}
