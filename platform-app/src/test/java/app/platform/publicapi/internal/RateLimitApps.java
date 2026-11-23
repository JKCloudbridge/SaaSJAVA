package app.platform.publicapi.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.identity.Users;
import app.platform.licensing.Plans;
import app.platform.licensing.Subscriptions;
import app.platform.testsupport.TestApplication;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations;
import app.platform.testsupport.TestOrganizations.Organization;
import app.platform.testsupport.TestPlatform;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Helpers for the limit tests that start application instances of their own (Sprint 32): an organization on a plan
 * with the feature, an application registered through the real management API and its token, and a request to the
 * public interface made the way another program makes it, to whichever instance the test names.
 */
final class RateLimitApps {

    /** An organization with a connected application and the token it holds. */
    record World(Organization organization, TestBrowser admin, String clientId, String secret, String token) {

        /** Prints identifiers only: the secret and the token are credentials. */
        @Override
        public String toString() {
            return "World[" + clientId + "]";
        }
    }

    private RateLimitApps() {
    }

    /** An organization and an application with all scopes, made through the instance {@code app}. */
    static World world(TestApplication app) {
        Organization organization = TestOrganizations.create(app.bean(Users.class));
        TestPlatform.subscribe(app.bean(Plans.class), app.bean(Subscriptions.class), organization.id(), 8, 3,
                "integrations");
        TestBrowser admin = TestOrganizations.signedIn(app.port(), organization.host(), organization.admin().person());
        Response registered = admin.postJson("/api/v1/connected-apps", "{\"name\":\"limits-app\",\"description\":\"\","
                + "\"scopes\":[\"objects.read\",\"records.read\",\"records.write\"]}");
        assertThat(registered.status()).as(registered.body()).isEqualTo(201);
        String clientId = JsonPath.read(registered.body(), "$.data.detail.app.clientId");
        String secret = JsonPath.read(registered.body(), "$.data.clientSecret");
        return new World(organization, admin, clientId, secret, token(app.port(), organization.host(), clientId,
                secret));
    }

    /** A token from the token endpoint of the instance on that port. */
    static String token(int port, String host, String clientId, String secret) {
        String basic = Base64.getEncoder().encodeToString((clientId + ":" + secret).getBytes(StandardCharsets.UTF_8));
        Response answer = new TestHttp(port).post("/api/v1/oauth2/token", "grant_type=client_credentials", "Host",
                host, "X-Forwarded-For", source(), "Authorization", "Basic " + basic, "Content-Type",
                "application/x-www-form-urlencoded");
        assertThat(answer.status()).as(answer.body()).isEqualTo(200);
        return JsonPath.read(answer.body(), "$.access_token");
    }

    /** The cheapest counted request, on the instance with that port. */
    static Response ask(int port, World world) {
        return new TestHttp(port).request("GET", "/api/public/v1/objects", "", "Host", world.organization().host(),
                "Authorization", "Bearer " + world.token(), "X-Forwarded-For", source());
    }

    private static String source() {
        return "2001:db8:" + Integer.toHexString(ThreadLocalRandom.current().nextInt(0x10000)) + ":"
                + Integer.toHexString(ThreadLocalRandom.current().nextInt(0x10000)) + "::1";
    }
}
