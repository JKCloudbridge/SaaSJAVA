package app.platform.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestHttp;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.tenancy.TenantFixtures;
import app.platform.testsupport.tenancy.TenantFixtures.TestTenant;
import app.platformapi.ApiPaths;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

/**
 * With the switch for a trusted proxy on (the local development proxy, a deployment's ingress), the organization comes
 * from {@code X-Forwarded-Host}; everything else stays the same, including the refusals.
 */
@PlatformIntegrationTest
@TestPropertySource(properties = "platform.tenancy.trust-forwarded-host=true")
class TenantResolutionForwardedHostIT {

    @LocalServerPort
    private int port;

    private Response get(String... headers) {
        return new TestHttp(port).get(ApiPaths.TENANT_CURRENT, headers);
    }

    @Test
    void theForwardedHostNamesTheOrganizationEvenThoughTheProxyRewroteTheHostHeader() {
        TestTenant tenant = TenantFixtures.createActiveTenant();

        Response response = get("Host", "localhost:8080", "X-Forwarded-Host", tenant.host() + ":3000");

        assertThat(response.status()).isEqualTo(200);
        assertThat(JsonPath.<String>read(response.body(), "$.data.slug")).isEqualTo(tenant.slug());
    }

    @Test
    void onlyTheFirstValueOfAProxyChainCounts() {
        TestTenant first = TenantFixtures.createActiveTenant();
        TestTenant second = TenantFixtures.createActiveTenant();

        Response response = get("Host", "localhost", "X-Forwarded-Host", first.host() + ", " + second.host());

        assertThat(JsonPath.<String>read(response.body(), "$.data.slug")).isEqualTo(first.slug());
    }

    @Test
    void withoutTheForwardedHeaderTheHostHeaderIsUsed() {
        TestTenant tenant = TenantFixtures.createActiveTenant();

        assertThat(get("Host", tenant.host()).status()).isEqualTo(200);
    }

    @Test
    void aForwardedHostOfAClosedOrUnknownOrganizationIsRefusedLikeAnyOther() {
        TestTenant suspended = TenantFixtures.createTenant(TenantStatus.SUSPENDED);

        assertThat(get("Host", "localhost", "X-Forwarded-Host", suspended.host()).status()).isEqualTo(403);
        assertThat(get("Host", "localhost", "X-Forwarded-Host", "nobody-here.platform.example.test").status())
                .isEqualTo(404);
    }

    @Test
    void aBlankForwardedHostFallsBackToTheHostHeader() {
        TestTenant tenant = TenantFixtures.createActiveTenant();

        Response response = get("Host", tenant.host(), "X-Forwarded-Host", " ");

        assertThat(response.status()).isEqualTo(200);
    }

    @Test
    void theForwardedHostOfAPlatformNameAddressesNoOrganization() {
        TestTenant tenant = TenantFixtures.createActiveTenant();

        Response response = get("Host", tenant.host(), "X-Forwarded-Host", "www.platform.example.test");

        assertThat(response.status()).as("the forwarded value wins, and it is a platform host").isEqualTo(404);
    }
}
