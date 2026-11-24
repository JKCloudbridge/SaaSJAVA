package app.platform.identity;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestSignIn;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Default deny, proven by walking every request mapping of the application: an endpoint answers a caller who is not
 * signed in with 401 unless it is on the short, explicit list of public endpoints below. Adding a controller without
 * thinking about who may call it therefore cannot make it public by accident: this test fails until the endpoint is
 * either protected (the default) or deliberately added to the list, with a reason, in the same change.
 */
@PlatformIntegrationTest
class EndpointExposureIT {

    /** The public endpoints and why each one is public. */
    private static final Set<String> PUBLIC = Set.of(
            "GET /api/v1/platform/status",       // readiness of the service: no data
            // the API description (ADR-0011): no data, and switched off in a deployment
            "GET /api/v1/openapi",
            // the name of the organization a host belongs to: the branding of the sign-in page
            "GET /api/v1/tenant/current",
            "GET /api/v1/auth/csrf",              // gives the page its forgery-protection cookie
            // begins the sign-in redirect; ends at the sign-in page when there is no login
            "GET /api/v1/auth/start",
            "GET /api/v1/auth/callback",          // checks its own state cookie; refuses without it
            "POST /api/v1/auth/sign-in",          // checks the password itself
            "POST /api/v1/auth/refresh",          // authenticates by the refresh cookie
            "POST /api/v1/auth/sign-out",         // works from whatever the browser holds; idempotent
            // Sprint 4: for people without a session. The request steps answer the same for every address and only
            // queue a mail; the completing steps are authorized by the one-time token of the e-mailed link.
            "POST /api/v1/auth/sign-up",
            "POST /api/v1/auth/sign-up/complete",
            "POST /api/v1/auth/password/forgot",
            "POST /api/v1/auth/password/reset",
            // Sprint 5: authorized by a one-time token and nothing else. Reading and accepting an invitation as a new
            // person prove themselves with the token of the mailed link; completing a switch proves itself with the
            // one-time, 60-second proof the other host handed out. All answer the same for every kind of bad token.
            "POST /api/v1/auth/invitations/preview",
            "POST /api/v1/auth/invitations/accept-new",
            "POST /api/v1/auth/switch/complete",
            // Sprint 28 (ADR-0221): the receiver of changes from an external system. The caller is not a person and
            // has no session; it proves itself with an HMAC over the exact bytes of the body, keyed with a credential
            // of the organization, and an unknown connection, a switched-off one, a stale time, a replay and a wrong
            // signature all get the same 401. The organization comes from the connection, never from the host.
            "POST /api/v1/inbound/x/x",
            // Sprint 30 (ADR-0248): the contract document of the public API, so that an integrator can read it before
            // holding any credential. It shows shapes and words, never data, and names no host.
            "GET /api/public/v1/openapi");

    @LocalServerPort
    private int port;

    @Autowired
    private RequestMappingHandlerMapping mappings;

    private record Endpoint(String method, String path) {

        String key() {
            return method + " " + path;
        }
    }

    private List<Endpoint> endpoints() {
        List<Endpoint> found = new ArrayList<>();
        for (RequestMappingInfo info : mappings.getHandlerMethods().keySet()) {
            if (info.getPathPatternsCondition() == null) {
                continue;
            }
            for (var pattern : info.getPathPatternsCondition().getPatterns()) {
                String path = pattern.getPatternString().replaceAll("\\{[^}]+}", "x");
                // The public API (Sprint 30, ADR-0244) is walked too: a prefix that no rule looked at would be open.
                if (!path.startsWith("/api/v1/") && !path.startsWith("/api/public/v1/")) {
                    continue;
                }
                Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
                if (methods.isEmpty()) {
                    found.add(new Endpoint("GET", path));
                }
                methods.forEach(method -> found.add(new Endpoint(method.name(), path)));
            }
        }
        return found;
    }

    @Test
    void everyEndpointAnswers401ToAnAnonymousCallerUnlessItIsOnTheExplicitPublicList() {
        TestBrowser anonymous = new TestBrowser(port, TestSignIn.PLATFORM_HOST);
        List<Endpoint> all = endpoints();
        assertThat(all).as("the walk found the endpoints").hasSizeGreaterThan(8);

        List<String> openedWithoutBeingListed = new ArrayList<>();
        for (Endpoint endpoint : all) {
            if (PUBLIC.contains(endpoint.key())) {
                continue;
            }
            // The forgery header is attached, so a refusal can only be about authentication.
            Response response = anonymous.request(endpoint.method(), endpoint.path(), "{}");
            if (response.status() != 401) {
                openedWithoutBeingListed.add(endpoint.key() + " answered " + response.status());
            }
        }

        assertThat(openedWithoutBeingListed).as("protected by default").isEmpty();
    }

    @Test
    void theExplicitPublicListNamesOnlyEndpointsThatExistSoARenameCannotLeaveAStaleOpening() {
        Set<String> existing = new TreeSet<>();
        endpoints().forEach(endpoint -> existing.add(endpoint.key()));

        assertThat(existing).containsAll(PUBLIC);
    }

    /**
     * Sprint 31 (ADR-0267, ADR-0271): the management of connected applications is on none of the lists above, so the
     * walk holds it to 401 without a sign-in like every other endpoint, and a token of an application never reaches it
     * (ClientTokenBoundaryIT). The token endpoint is the library's, not a mapping of ours: it is public in the sense
     * that it proves a client by its secret, so without one it answers a refusal and never a token.
     */
    @Test
    void theManagementOfConnectedApplicationsIsNotPublicAndTheTokenEndpointNeedsAClientToProveItself() {
        Set<String> existing = new TreeSet<>();
        endpoints().forEach(endpoint -> existing.add(endpoint.key()));
        TestBrowser anonymous = new TestBrowser(port, TestSignIn.PLATFORM_HOST);

        assertThat(PUBLIC).noneMatch(key -> key.contains("/connected-apps"));
        assertThat(existing).contains("GET /api/v1/connected-apps", "POST /api/v1/connected-apps",
                "GET /api/v1/connected-apps/catalogue", "GET /api/v1/connected-apps/x", "PUT /api/v1/connected-apps/x",
                "DELETE /api/v1/connected-apps/x", "POST /api/v1/connected-apps/x/disable",
                "POST /api/v1/connected-apps/x/enable", "POST /api/v1/connected-apps/x/secrets/rotate",
                "DELETE /api/v1/connected-apps/x/secrets/x");
        existing.stream().filter(key -> key.contains("/connected-apps")).forEach(key -> {
            String[] parts = key.split(" ");
            assertThat(anonymous.request(parts[0], parts[1], "{}").status()).as(key).isEqualTo(401);
        });
        Response token = new TestHttp(port).post("/api/v1/oauth2/token", "grant_type=client_credentials",
                "Host", TestSignIn.PLATFORM_HOST, "Content-Type", "application/x-www-form-urlencoded");
        assertThat(token.status()).isIn(400, 401);
        assertThat(token.body()).doesNotContain("access_token");
    }

    /**
     * Sprint 32 (ADR-0274, ADR-0280, ADR-0283): the webhooks, the usage page and the limits console are on none of the
     * public lists, so the walk holds every one of them to 401 without a sign-in; each exists, so a rename cannot leave
     * a stale entry; and a token of a connected application never reaches them (ClientTokenAccessTest).
     */
    @Test
    void theWebhooksTheUsagePageAndTheLimitsConsoleAreNotPublic() {
        Set<String> existing = new TreeSet<>();
        endpoints().forEach(endpoint -> existing.add(endpoint.key()));
        TestBrowser anonymous = new TestBrowser(port, TestSignIn.PLATFORM_HOST);

        assertThat(PUBLIC).noneMatch(key -> key.contains("/webhooks") || key.contains("/usage")
                || key.contains("/limits"));
        assertThat(existing).contains("GET /api/v1/webhooks", "POST /api/v1/webhooks",
                "GET /api/v1/webhooks/catalogue", "GET /api/v1/webhooks/x", "PUT /api/v1/webhooks/x",
                "DELETE /api/v1/webhooks/x", "POST /api/v1/webhooks/x/test", "POST /api/v1/webhooks/x/enable",
                "POST /api/v1/webhooks/x/disable", "POST /api/v1/webhooks/x/secret/rotate",
                "POST /api/v1/webhooks/x/secret/end-overlap", "GET /api/v1/webhooks/x/deliveries",
                "GET /api/v1/webhooks/x/deliveries/x/attempts", "POST /api/v1/webhooks/x/deliveries/x/replay",
                "POST /api/v1/webhooks/x/deliveries/replay", "GET /api/v1/usage",
                "GET /api/v1/platform/organizations/x/limits", "PUT /api/v1/platform/organizations/x/limits",
                "POST /api/v1/platform/organizations/x/limits/reset");
        existing.stream().filter(key -> key.contains("/webhooks") || key.contains("/usage")
                || key.contains("/limits")).forEach(key -> {
            String[] parts = key.split(" ");
            assertThat(anonymous.request(parts[0], parts[1], "{}").status()).as(key).isEqualTo(401);
        });
    }

    @Test
    void aPathThatDoesNotExistIsAlsoRefusedBeforeAnythingIsSaid() {
        TestBrowser anonymous = new TestBrowser(port, TestSignIn.PLATFORM_HOST);

        assertThat(anonymous.get("/api/v1/does-not-exist").status()).isEqualTo(401);
        assertThat(anonymous.get("/api/public/v1/does-not-exist").status()).isEqualTo(401);
        assertThat(anonymous.get("/something-outside-the-api").status()).isEqualTo(401);
    }
}
