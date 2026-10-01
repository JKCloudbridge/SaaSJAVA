package app.platform.identity;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestSignIn;
import app.platform.testsupport.TestUsers;
import app.platform.testsupport.TestUsers.TestUser;
import app.platform.testsupport.tenancy.TenantFixtures;
import app.platform.testsupport.tenancy.TenantFixtures.TestTenant;
import app.webtest.IdentityTestController;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

/**
 * The user comes from the authenticated identity, the tenant comes only from the host (stories S3-SEC-21 and the
 * "swapped header" exit criterion), and a refusal of authentication or permission is answered in the error model (the
 * carry-over from Sprint 1: a security exception must never become a 500).
 */
@PlatformIntegrationTest
@Import(IdentityTestController.class)
class TenantAndUserContextIT {

    private static final String CONTEXT = "/api/v1/test/security/context";

    @LocalServerPort
    private int port;

    @Autowired
    private Users users;

    // ---- the user is filled from the authenticated identity ----

    @Test
    void onAnOrganizationHostTheContextHoldsTheTenantOfTheHostAndTheUserOfTheToken() {
        TestUser user = TestUsers.create(users);
        TestTenant tenant = TenantFixtures.createActiveTenant();
        TestBrowser browser = new TestBrowser(port, tenant.host());
        browser.signIn(user.email(), user.password());

        Response response = browser.get(CONTEXT);

        assertThat(response.status()).isEqualTo(200);
        assertThat(JsonPath.<String>read(response.body(), "$.data.tenantId")).isEqualTo(tenant.id().toString());
        assertThat(JsonPath.<String>read(response.body(), "$.data.userId")).isEqualTo(user.user().id().toString());
        assertThat(JsonPath.<String>read(response.body(), "$.data.membershipId"))
                .as("the membership is Sprint 5").isEmpty();
    }

    @Test
    void onThePlatformHostThereIsNoTenantContextButTheCallerIsStillKnown() {
        TestUser user = TestUsers.create(users);
        TestBrowser browser = new TestBrowser(port, TestSignIn.PLATFORM_HOST);
        browser.signIn(user.email(), user.password());

        assertThat(JsonPath.<String>read(browser.get(CONTEXT).body(), "$.data.tenantId")).isEmpty();
        assertThat(JsonPath.<String>read(browser.get("/api/v1/auth/me").body(), "$.data.id"))
                .isEqualTo(user.user().id().toString());
    }

    // ---- the user can not choose the tenant ----

    @Test
    void aSwappedHeaderOrParameterOrBodyNamingAnotherTenantChangesNothing() {
        TestUser user = TestUsers.create(users);
        TestTenant mine = TenantFixtures.createActiveTenant();
        TestTenant other = TenantFixtures.createActiveTenant();
        TestBrowser browser = new TestBrowser(port, mine.host());
        TestBrowser.Session session = browser.signIn(user.email(), user.password());
        TestHttp http = new TestHttp(port, "Host", mine.host(), "Authorization", session.bearer());

        List<Response> attempts = List.of(
                http.get(CONTEXT, "X-Tenant-Id", other.id().toString()),
                http.get(CONTEXT, "X-Tenant", other.slug()),
                http.get(CONTEXT, "X-Forwarded-Host", other.host()),
                http.get(CONTEXT, "Forwarded", "host=" + other.host()),
                http.get(CONTEXT + "?tenantId=" + other.id() + "&tenant=" + other.slug()),
                http.get(CONTEXT, "Referer", "http://" + other.host() + "/"),
                http.get(CONTEXT, "Origin", "http://" + other.host()));

        for (Response attempt : attempts) {
            assertThat(attempt.status()).isEqualTo(200);
            assertThat(JsonPath.<String>read(attempt.body(), "$.data.tenantId")).isEqualTo(mine.id().toString());
        }
    }

    @Test
    void aTokenFromOneOrganizationCannotBePointedAtAnotherByTheHostEither() {
        TestUser user = TestUsers.create(users);
        TestTenant mine = TenantFixtures.createActiveTenant();
        TestTenant other = TenantFixtures.createActiveTenant();
        TestBrowser.Session session = new TestBrowser(port, mine.host()).signIn(user.email(), user.password());

        Response viaOtherHost = new TestHttp(port).get(CONTEXT, "Host", other.host(), "Authorization",
                session.bearer());

        assertThat(viaOtherHost.status()).isEqualTo(401);
    }

    @Test
    void aClosedOrUnknownOrganizationHostStaysRefusedForASignedInUser() {
        TestUser user = TestUsers.create(users);
        TestBrowser.Session session = new TestBrowser(port, TestSignIn.PLATFORM_HOST)
                .signIn(user.email(), user.password());

        Response unknown = new TestHttp(port).get(CONTEXT, "Host", "no-such-organization.platform.example.test",
                "Authorization", session.bearer());

        assertThat(unknown.status()).isEqualTo(404);
    }

    // ---- 401 and 403 in the error model ----

    @Test
    void anAuthenticationFailureThrownInsideAControllerIsA401NotA500() {
        TestUser user = TestUsers.create(users);
        TestBrowser.Session session = new TestBrowser(port, TestSignIn.PLATFORM_HOST)
                .signIn(user.email(), user.password());

        Response response = new TestHttp(port).get("/api/v1/test/security/unauthenticated", "Host",
                TestSignIn.PLATFORM_HOST, "Authorization", session.bearer());

        assertThat(response.status()).isEqualTo(401);
        assertThat(JsonPath.<String>read(response.body(), "$.error.code")).isEqualTo("UNAUTHENTICATED");
        assertThat(response.body()).doesNotContain("SECRET-INTERNAL");
        assertThat(response.header("WWW-Authenticate")).hasValue("Bearer");
    }

    @Test
    void anAccessDeniedThrownInsideAControllerIsA403NotA500() {
        TestUser user = TestUsers.create(users);
        TestBrowser.Session session = new TestBrowser(port, TestSignIn.PLATFORM_HOST)
                .signIn(user.email(), user.password());

        Response response = new TestHttp(port).get("/api/v1/test/security/denied", "Host", TestSignIn.PLATFORM_HOST,
                "Authorization", session.bearer());

        assertThat(response.status()).isEqualTo(403);
        assertThat(JsonPath.<String>read(response.body(), "$.error.code")).isEqualTo("FORBIDDEN");
        assertThat(JsonPath.<String>read(response.body(), "$.error.requestId")).isNotBlank();
        assertThat(response.body()).doesNotContain("SECRET-INTERNAL");
    }

    @Test
    void theFilterChainsOwnRefusalsCarryTheRequestIdAndTheTraceId() {
        Response unauthenticated = new TestHttp(port).get("/api/v1/auth/me", "Host", TestSignIn.PLATFORM_HOST,
                "X-Request-ID", "client-req-security-1", "traceparent",
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");

        assertThat(unauthenticated.status()).isEqualTo(401);
        assertThat(JsonPath.<String>read(unauthenticated.body(), "$.error.requestId"))
                .isEqualTo("client-req-security-1");
        assertThat(JsonPath.<String>read(unauthenticated.body(), "$.error.traceId"))
                .isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
        assertThat(unauthenticated.header("X-Request-ID")).hasValue("client-req-security-1");
    }

    @Test
    void aMissingForgeryHeaderIsA403InTheErrorModelWithNoDetail() {
        TestUser user = TestUsers.create(users);
        TestBrowser browser = new TestBrowser(port, TestSignIn.PLATFORM_HOST);
        browser.signIn(user.email(), user.password());

        Response response = browser.postJsonWithoutCsrfHeader("/api/v1/auth/sign-out-all", "{}");

        assertThat(response.status()).isEqualTo(403);
        assertThat(JsonPath.<String>read(response.body(), "$.error.code")).isEqualTo("FORBIDDEN");
        assertThat(browser.get("/api/v1/auth/me").status()).as("and nothing was done").isEqualTo(200);
    }

    @Test
    void aProgramThatSendsAnAuthorizationHeaderNeedsNoForgeryHeader() {
        TestUser user = TestUsers.create(users);
        TestBrowser.Session session = new TestBrowser(port, TestSignIn.PLATFORM_HOST)
                .signIn(user.email(), user.password());

        Response response = new TestHttp(port).post("/api/v1/auth/sign-out", "", "Host", TestSignIn.PLATFORM_HOST,
                "Authorization", session.bearer());

        assertThat(response.status()).isEqualTo(204);
    }
}
