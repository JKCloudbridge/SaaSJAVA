package app.platform.identity;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp;
import app.platform.testsupport.TestUsers;
import app.platform.testsupport.tenancy.TenantFixtures;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

@PlatformIntegrationTest
class SignInSmokeIT {

    @LocalServerPort
    private int port;

    @Autowired
    private Users users;

    @Test
    void aUserSignsInOnAnOrganizationHostAndIsKnownToTheApi() {
        TestUsers.TestUser user = TestUsers.create(users);
        String host = TenantFixtures.createActiveTenant().host();
        TestBrowser browser = new TestBrowser(port, host);

        TestBrowser.Session session = browser.signIn(user.email(), user.password());

        TestHttp.Response me = browser.get("/api/v1/auth/me");
        assertThat(me.status()).isEqualTo(200);
        assertThat(JsonPath.<String>read(me.body(), "$.data.email")).isEqualTo(user.email());
        TestHttp.Response bearer = new TestHttp(port).get("/api/v1/auth/me", "Host", host,
                "Authorization", session.bearer());
        assertThat(bearer.status()).isEqualTo(200);
    }
}
