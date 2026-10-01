package app.platform.identity;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
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
            "POST /api/v1/auth/sign-out");        // works from whatever the browser holds; idempotent

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
                if (!path.startsWith("/api/v1/")) {
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

    @Test
    void aPathThatDoesNotExistIsAlsoRefusedBeforeAnythingIsSaid() {
        TestBrowser anonymous = new TestBrowser(port, TestSignIn.PLATFORM_HOST);

        assertThat(anonymous.get("/api/v1/does-not-exist").status()).isEqualTo(401);
        assertThat(anonymous.get("/something-outside-the-api").status()).isEqualTo(401);
    }
}
