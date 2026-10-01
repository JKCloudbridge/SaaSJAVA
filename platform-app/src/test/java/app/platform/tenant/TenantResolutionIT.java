package app.platform.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestHttp;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.tenancy.TenantFixtures;
import app.platform.testsupport.tenancy.TenantFixtures.TestTenant;
import app.platformapi.ApiHeaders;
import app.platformapi.ApiPaths;
import com.jayway.jsonpath.JsonPath;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Tenant resolution by host name on the real stack (ADR-0017): an open organization is served, an unknown or closed one
 * is refused in the platform's error model, the tenant can never come from anything but the host, and organizations
 * never see each other. This is the authorization test of the one endpoint of the sprint: allowed (an open host),
 * denied (unknown or closed hosts) and cross-tenant (a host, header, parameter or body naming another tenant).
 */
@PlatformIntegrationTest
class TenantResolutionIT {

    private static final String PATH = ApiPaths.TENANT_CURRENT;
    private static TestTenant tenantA;
    private static TestTenant tenantB;

    @LocalServerPort
    private int port;

    @BeforeAll
    static void createTenants() {
        tenantA = TenantFixtures.createActiveTenant();
        tenantB = TenantFixtures.createActiveTenant();
    }

    private Response get(String host, String... more) {
        String[] headers = new String[more.length + 2];
        headers[0] = "Host";
        headers[1] = host;
        System.arraycopy(more, 0, headers, 2, more.length);
        return new TestHttp(port).get(PATH, headers);
    }

    // ---- allowed ----

    @Test
    void anOpenOrganizationHostIsServedAndNamesItsOrganization() {
        Response response = get(tenantA.host());

        assertThat(response.status()).isEqualTo(200);
        assertThat(JsonPath.<String>read(response.body(), "$.data.slug")).isEqualTo(tenantA.slug());
        assertThat(JsonPath.<String>read(response.body(), "$.data.displayName")).isEqualTo("Test " + tenantA.slug());
        assertThat(JsonPath.<Map<String, Object>>read(response.body(), "$.data"))
                .containsOnlyKeys("slug", "displayName");
        assertThat(response.header(ApiHeaders.REQUEST_ID)).isPresent();
    }

    @Test
    void hostNameCaseAndAPortDoNotMatter() {
        assertThat(get(tenantA.host().toUpperCase() + ":8443").status()).isEqualTo(200);
    }

    // ---- denied ----

    @Test
    void anUnknownOrganizationIsNotFoundInTheErrorModel() {
        Response response = get("no-such-organization.platform.example.test");

        assertThat(response.status()).isEqualTo(404);
        assertThat(JsonPath.<String>read(response.body(), "$.error.code")).isEqualTo("NOT_FOUND");
        assertThat(JsonPath.<String>read(response.body(), "$.error.requestId")).isNotBlank();
        assertThat(response.header(ApiHeaders.REQUEST_ID)).isPresent();
        assertThat(response.body()).doesNotContain("no-such-organization");
    }

    @Test
    void aSuspendedDeactivatedOrStillProvisioningOrganizationIsUnavailableAndLooksTheSame() {
        TestTenant suspended = TenantFixtures.createTenant(TenantStatus.SUSPENDED);
        TestTenant deactivated = TenantFixtures.createTenant(TenantStatus.DEACTIVATED);
        TestTenant provisioning = TenantFixtures.createTenant(TenantStatus.PROVISIONING);

        String message = null;
        for (TestTenant tenant : new TestTenant[] {suspended, deactivated, provisioning}) {
            Response response = get(tenant.host());

            assertThat(response.status()).as(tenant.slug()).isEqualTo(403);
            assertThat(JsonPath.<String>read(response.body(), "$.error.code")).isEqualTo("TENANT_UNAVAILABLE");
            assertThat(response.body()).doesNotContainIgnoringCase("suspended")
                    .doesNotContainIgnoringCase("deactivated")
                    .doesNotContainIgnoringCase("provisioning").doesNotContain(tenant.slug());
            String current = JsonPath.read(response.body(), "$.error.message");
            assertThat(message == null || message.equals(current))
                    .as("the same answer for every closed state").isTrue();
            message = current;
        }
    }

    @Test
    void everyApiPathIsRefusedForAnUnavailableOrganizationNotJustTheTenantEndpoint() {
        TestTenant suspended = TenantFixtures.createTenant(TenantStatus.SUSPENDED);

        Response response = new TestHttp(port).get(ApiPaths.PLATFORM_STATUS, "Host", suspended.host());

        assertThat(response.status()).isEqualTo(403);
        assertThat(JsonPath.<String>read(response.body(), "$.error.code")).isEqualTo("TENANT_UNAVAILABLE");
    }

    @Test
    void aStatusChangeTakesEffectOnTheNextRequest() throws Exception {
        TestTenant tenant = TenantFixtures.createActiveTenant();
        assertThat(get(tenant.host()).status()).isEqualTo(200);

        setStatus(tenant, "SUSPENDED");
        assertThat(get(tenant.host()).status()).isEqualTo(403);

        setStatus(tenant, "ACTIVE");
        assertThat(get(tenant.host()).status()).isEqualTo(200);

        setStatus(tenant, "DEACTIVATED");
        assertThat(get(tenant.host()).status()).isEqualTo(403);
    }

    @Test
    void aHostThatCanNeverBeAnOrganizationIsNotFound() {
        assertThat(get("a.b.platform.example.test").status()).isEqualTo(404);
        assertThat(get("ab.platform.example.test").status()).isEqualTo(404);
        // The container itself refuses a malformed host name before the platform sees the request.
        assertThat(get("Bad_Slug.platform.example.test").status()).isEqualTo(400);
    }

    @Test
    void thePlatformsOwnHostsAndForeignHostsAddressNoOrganization() {
        for (String host : new String[] {"platform.example.test", "www.platform.example.test",
            "admin.platform.example.test", "localhost", "127.0.0.1"}) {
            Response response = get(host);

            assertThat(response.status()).as(host).isEqualTo(404);
            assertThat(JsonPath.<String>read(response.body(), "$.error.code")).as(host).isEqualTo("NOT_FOUND");
        }
    }

    @Test
    void platformEndpointsNeedNoTenant() {
        assertThat(new TestHttp(port).get(ApiPaths.PLATFORM_STATUS, "Host", "localhost").status()).isEqualTo(200);
        assertThat(new TestHttp(port).get(ApiPaths.PLATFORM_STATUS, "Host", "platform.example.test").status())
                .isEqualTo(200);
    }

    // ---- cross-tenant: the tenant comes from the host and nowhere else ----

    @Test
    void eachOrganizationSeesItselfAndNeverTheOther() {
        assertThat(JsonPath.<String>read(get(tenantA.host()).body(), "$.data.slug")).isEqualTo(tenantA.slug());
        assertThat(JsonPath.<String>read(get(tenantB.host()).body(), "$.data.slug")).isEqualTo(tenantB.slug());
    }

    @Test
    void aHeaderNamingAnotherTenantChangesNothing() {
        Response response = get(tenantA.host(), "X-Tenant-ID", tenantB.id().toString(), "X-Tenant", tenantB.slug(),
                "Tenant-Id", tenantB.id().toString(), "X-Tenant-Slug", tenantB.slug());

        assertThat(response.status()).isEqualTo(200);
        assertThat(JsonPath.<String>read(response.body(), "$.data.slug")).isEqualTo(tenantA.slug());
    }

    @Test
    void aHeaderCanNotGiveATenantToAPlatformHost() {
        Response response = get("platform.example.test", "X-Tenant-ID", tenantA.id().toString(), "X-Tenant",
                tenantA.slug());

        assertThat(response.status()).isEqualTo(404);
    }

    @Test
    void aForwardedHostHeaderIsIgnoredUnlessTheDeploymentTrustsItsProxy() {
        // The default is off: with the API reachable directly, a caller could otherwise name any organization.
        Response toPlatformHost = get("platform.example.test", "X-Forwarded-Host", tenantA.host());
        Response toOtherTenant = get(tenantA.host(), "X-Forwarded-Host", tenantB.host());

        assertThat(toPlatformHost.status()).isEqualTo(404);
        assertThat(JsonPath.<String>read(toOtherTenant.body(), "$.data.slug")).isEqualTo(tenantA.slug());
    }

    @Test
    void aQueryParameterOrBodyNamingAnotherTenantChangesNothing() {
        Response query = new TestHttp(port).get(PATH + "?tenant=" + tenantB.slug() + "&tenantId=" + tenantB.id(),
                "Host", tenantA.host());
        Response body = new TestHttp(port).post(PATH, "{\"tenantId\":\"" + tenantB.id() + "\"}", "Host", tenantA.host(),
                "Content-Type", "application/json");

        assertThat(JsonPath.<String>read(query.body(), "$.data.slug")).isEqualTo(tenantA.slug());
        assertThat(body.status()).as("the endpoint takes no body").isEqualTo(405);
    }

    @Test
    void theAnswerNeverContainsAnyIdentifier() {
        assertThat(get(tenantA.host()).body()).doesNotContain(tenantA.id().toString());
    }

    private static void setStatus(TestTenant tenant, String status) throws Exception {
        try (Connection owner = app.platform.testsupport.TestDatabase.ownerConnection();
                PreparedStatement update = owner.prepareStatement(
                        "update tenant set status = ?, updated_by = ?, version = version + 1 where id = ?")) {
            update.setString(1, status);
            update.setObject(2, app.platform.sharedkernel.ActorId.SYSTEM.value());
            update.setObject(3, tenant.id().value());
            update.executeUpdate();
        }
    }
}
