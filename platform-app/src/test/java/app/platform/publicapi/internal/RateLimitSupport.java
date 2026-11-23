package app.platform.publicapi.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations.Organization;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the tests of the limits share (Sprint 32): an organization with a connected application that holds a token, the
 * cheapest counted request (the list of objects), and the reading of the two rate-limit headers.
 */
abstract class RateLimitSupport extends ClientSupport {

    /** An application with its token, ready to call the public API. */
    protected record Program(Registered app, Caller caller) {

        Response ask() {
            return caller.get(OBJECTS);
        }
    }

    protected Program program(Organization organization, TestBrowser admin) {
        Registered app = register(admin, ALL_SCOPES);
        return new Program(app, clientCaller(organization, app));
    }

    /** What the header {@code RateLimit} says is left in the window of that name, or -1 when it is not there. */
    protected static long remaining(Response response, String window) {
        return number(response.header("RateLimit").orElse(""), window, "r");
    }

    /** The seconds until the window of that name ends, as the header {@code RateLimit} says, or -1. */
    protected static long resetsIn(Response response, String window) {
        return number(response.header("RateLimit").orElse(""), window, "t");
    }

    /** The most the window allows, as the header {@code RateLimit-Policy} says, or -1. */
    protected static long quota(Response response, String window) {
        return number(response.header("RateLimit-Policy").orElse(""), window, "q");
    }

    private static long number(String header, String window, String key) {
        Matcher item = Pattern.compile("\"" + window + "\";" + key + "=([0-9]+)").matcher(header);
        return item.find() ? Long.parseLong(item.group(1)) : -1;
    }

    protected static void assertRefusedWithTheTimeToWait(Response refused) {
        assertThat(refused.status()).as(refused.body()).isEqualTo(429);
        assertThat(refused.header("Retry-After")).isPresent();
        assertThat(refused.header("Retry-After").orElseThrow()).matches("[0-9]+");
        assertThat(Long.parseLong(refused.header("Retry-After").orElseThrow())).isBetween(1L, 86_400L);
        assertThat(refused.header("RateLimit")).isPresent();
        assertThat(refused.header("RateLimit-Policy")).isPresent();
    }
}
