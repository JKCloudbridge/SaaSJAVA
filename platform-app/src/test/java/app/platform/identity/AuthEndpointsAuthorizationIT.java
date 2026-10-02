package app.platform.identity;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestMembers;
import app.platform.testsupport.TestSignIn;
import app.platform.testsupport.TestUsers;
import app.platform.testsupport.TestUsers.TestUser;
import app.platform.testsupport.tenancy.TenantFixtures;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The authorization test of Definition of Done for the protected endpoints of Sprint 3: allowed (a caller with a valid
 * token on the right host), denied (no token, a revoked one) and cross-tenant (a token of another organization's host).
 */
@PlatformIntegrationTest
class AuthEndpointsAuthorizationIT {

    private record Protected(String method, String path, String body, int allowed) {
    }

    /** The protected endpoints of this sprint and what a valid caller gets. */
    private static final List<Protected> PROTECTED = List.of(
            new Protected("GET", "/api/v1/auth/me", null, 200),
            new Protected("POST", "/api/v1/auth/sign-out-all", "{}", 204),
            new Protected("POST", "/api/v1/auth/password",
                    "{\"currentPassword\":\"wrong current password\",\"newPassword\":\"a violet lantern 4321 ocean\"}",
                    400));

    @LocalServerPort
    private int port;

    @Autowired
    private Users users;

    private Response call(Protected endpoint, String host, String bearer) {
        if (bearer == null) {
            // An anonymous browser, with the forgery header, so that the answer is about authentication only (an
            // anonymous request without the header is refused earlier, with 403).
            return new TestBrowser(port, host).request(endpoint.method(), endpoint.path(), endpoint.body());
        }
        return new TestHttp(port, "Host", host, "Content-Type", "application/json")
                .request(endpoint.method(), endpoint.path(), endpoint.body(), "Authorization", bearer);
    }

    @Test
    void allowedADeniedAndACrossTenantCallerForEveryProtectedEndpoint() {
        String hostA = TenantFixtures.createActiveTenant().host();
        String hostB = TenantFixtures.createActiveTenant().host();

        for (Protected endpoint : PROTECTED) {
            TestUser user = TestUsers.create(users);
            TestMembers.addForHost(hostA, user.user().id());
            String bearer = new TestBrowser(port, hostA).signIn(user.email(), user.password()).bearer();

            assertThat(call(endpoint, hostA, null).status()).as("denied: no token, " + endpoint.path())
                    .isEqualTo(401);
            assertThat(call(endpoint, hostA, "Bearer revoked-or-invented").status()
                    ).as("denied: a token that is not alive, " + endpoint.path()).isEqualTo(401);
            assertThat(call(endpoint, hostB, bearer).status()).as("cross-tenant: " + endpoint.path()).isEqualTo(401);
            assertThat(call(endpoint, TestSignIn.PLATFORM_HOST, bearer).status())
                    .as("cross-host: " + endpoint.path()).isEqualTo(401);
            assertThat(call(endpoint, hostA, bearer).status()).as("allowed: " + endpoint.path())
                    .isEqualTo(endpoint.allowed());
        }
    }

    @Test
    void aRevokedTokenIsDeniedOnEveryProtectedEndpoint() {
        for (Protected endpoint : PROTECTED) {
            TestUser user = TestUsers.create(users);
            TestBrowser browser = new TestBrowser(port, TestSignIn.PLATFORM_HOST);
            String bearer = browser.signIn(user.email(), user.password()).bearer();
            browser.post("/api/v1/auth/sign-out");

            assertThat(call(endpoint, TestSignIn.PLATFORM_HOST, bearer).status()).as(endpoint.path())
                    .isEqualTo(401);
        }
    }
}
