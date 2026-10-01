package app.platform.identity;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestSignIn;
import app.platform.testsupport.TestUsers;
import app.platform.testsupport.TestUsers.TestUser;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Story S3-SEC-18: a request that the browser authenticates by cookie, and that changes something, needs the forgery
 * header. The regression this guards: the framework exempts every request that "has a bearer token" from the check, and
 * a resolver that also read the cookie once made every cookie-authenticated request exempt.
 */
@PlatformIntegrationTest
class ForgeryProtectionIT {

    private static final List<String> STATE_CHANGING = List.of(
            "/api/v1/auth/sign-out", "/api/v1/auth/sign-out-all", "/api/v1/auth/refresh", "/api/v1/auth/password",
            "/api/v1/auth/sign-in");

    @LocalServerPort
    private int port;

    @Autowired
    private Users users;

    @Test
    void everyStateChangingEndpointRefusesACookieOnlyRequestWithoutTheForgeryHeader() {
        TestUser user = TestUsers.create(users);
        TestBrowser browser = new TestBrowser(port, TestSignIn.PLATFORM_HOST);
        TestBrowser.Session session = browser.signIn(user.email(), user.password());
        TestHttp attacker = new TestHttp(port, "Host", TestSignIn.PLATFORM_HOST);
        String cookies = "platform_at=" + session.accessToken() + "; platform_rt=" + session.refreshToken();

        for (String path : STATE_CHANGING) {
            // The browser attaches the cookies by itself to a form posted from any other site; no header can be set.
            Response noHeader = attacker.post(path, "{\"email\":\"a@example.test\",\"password\":\"x\","
                    + "\"currentPassword\":\"x\",\"newPassword\":\"y\"}", "Cookie", cookies,
                    "Content-Type", "application/json");
            Response wrongHeader = attacker.post(path, "{}", "Cookie", cookies + "; XSRF-TOKEN=real", "X-XSRF-TOKEN",
                    "forged", "Content-Type", "application/json");

            assertThat(noHeader.status()).as(path).isEqualTo(403);
            assertThat(JsonPath.<String>read(noHeader.body(), "$.error.code")).as(path).isEqualTo("FORBIDDEN");
            assertThat(wrongHeader.status()).as(path + " with a wrong header").isEqualTo(403);
        }
        assertThat(browser.get("/api/v1/auth/me").status()).as("and nothing was done to the session").isEqualTo(200);
    }

    @Test
    void aFormPostedFromAnotherSiteCannotSignTheUserOut() {
        TestUser user = TestUsers.create(users);
        TestBrowser browser = new TestBrowser(port, TestSignIn.PLATFORM_HOST);
        TestBrowser.Session session = browser.signIn(user.email(), user.password());

        Response forged = new TestHttp(port, "Host", TestSignIn.PLATFORM_HOST).post("/api/v1/auth/sign-out", "_csrf=",
                "Cookie", "platform_at=" + session.accessToken(), "Content-Type",
                "application/x-www-form-urlencoded", "Origin", "https://evil.example.test");

        assertThat(forged.status()).isEqualTo(403);
        assertThat(browser.get("/api/v1/auth/me").status()).isEqualTo(200);
    }

    @Test
    void theSameRequestWithTheHeaderThePageSendsIsAccepted() {
        TestUser user = TestUsers.create(users);
        TestBrowser browser = new TestBrowser(port, TestSignIn.PLATFORM_HOST);
        browser.signIn(user.email(), user.password());

        assertThat(browser.post("/api/v1/auth/sign-out").status()).isEqualTo(204);
    }

    @Test
    void readingRequestsNeedNoForgeryHeader() {
        TestUser user = TestUsers.create(users);
        TestBrowser browser = new TestBrowser(port, TestSignIn.PLATFORM_HOST);
        browser.signIn(user.email(), user.password());

        assertThat(browser.get("/api/v1/auth/me").status()).isEqualTo(200);
    }
}
