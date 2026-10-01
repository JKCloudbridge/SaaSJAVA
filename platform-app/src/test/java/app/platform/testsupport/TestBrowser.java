package app.platform.testsupport;

import app.platform.testsupport.TestHttp.Response;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A minimal browser for sign-in tests: it keeps cookies between requests, never follows redirects on its own (a test
 * looks at every step), and sends the forgery-protection header the way the web app does. Talks to a real server on a
 * given host name, so the same code proves sign-in on an organization host and on the platform host.
 *
 * <p>Cookie paths are not applied (every cookie is sent with every request); the flags a cookie carries are checked by
 * tests that read the {@code Set-Cookie} headers themselves.
 */
public final class TestBrowser {

    /** The tokens a completed sign-in leaves in the cookies. */
    public record Session(String accessToken, String refreshToken) {

        /** The header value that presents the access token the way another program would. */
        public String bearer() {
            return "Bearer " + accessToken;
        }
    }

    private final TestHttp http;
    private final String host;
    private final Map<String, String> cookies = new LinkedHashMap<>();
    private String source = randomSource();

    /**
     * @param port the port of the running server
     * @param host the host name the browser "types": an organization host or the platform host
     */
    public TestBrowser(int port, String host) {
        this.http = new TestHttp(port);
        this.host = host;
    }

    /**
     * Every browser comes from its own network source (an IPv6 /64 of the documentation range), as the test profile
     * trusts {@code X-Forwarded-For}: rate limits are per source, and tests must not use each other's allowance.
     */
    private static String randomSource() {
        java.util.concurrent.ThreadLocalRandom random = java.util.concurrent.ThreadLocalRandom.current();
        return String.format("2001:db8:%x:%x:%x::1", random.nextInt(0x10000), random.nextInt(0x10000),
                random.nextInt(0x10000));
    }

    /** Makes this browser come from the given source address. */
    public TestBrowser fromSource(String address) {
        this.source = address;
        return this;
    }

    /** The current value of a cookie, if the browser holds it. */
    public Optional<String> cookie(String name) {
        return Optional.ofNullable(cookies.get(name));
    }

    /** Removes a cookie, as the browser does when it expires. */
    public void forget(String name) {
        cookies.remove(name);
    }

    /** Sets a cookie by hand (to replay a stolen value, or to forge one). */
    public void put(String name, String value) {
        cookies.put(name, value);
    }

    public Response get(String path, String... headers) {
        return remember(http.get(path, withBrowserHeaders(headers)));
    }

    /** A state-changing request with a JSON body and the forgery-protection header taken from the cookie. */
    public Response postJson(String path, String json, String... headers) {
        ensureCsrfCookie();
        return remember(http.post(path, json, withBrowserHeaders(overriding(
                List.of("Content-Type", "application/json", "X-XSRF-TOKEN", cookies.getOrDefault("XSRF-TOKEN", "")),
                headers))));
    }

    /** A state-changing request without a body. */
    public Response post(String path, String... headers) {
        ensureCsrfCookie();
        return remember(http.post(path, "", withBrowserHeaders(overriding(
                List.of("X-XSRF-TOKEN", cookies.getOrDefault("XSRF-TOKEN", "")), headers))));
    }

    /** Any method, with the forgery-protection header attached the way the web app does. */
    public Response request(String method, String path, String body, String... headers) {
        ensureCsrfCookie();
        return remember(http.request(method, path, body, withBrowserHeaders(overriding(
                List.of("Content-Type", "application/json", "X-XSRF-TOKEN", cookies.getOrDefault("XSRF-TOKEN", "")),
                headers))));
    }

    /** A state-changing request that deliberately leaves the forgery-protection header out. */
    public Response postJsonWithoutCsrfHeader(String path, String json) {
        ensureCsrfCookie();
        return remember(http.post(path, json, withBrowserHeaders("Content-Type", "application/json")));
    }

    /** The password check only: leaves the login cookie. */
    public Response signInPassword(String email, String password) {
        return postJson("/api/v1/auth/sign-in", "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}");
    }

    /**
     * The whole sign-in as a browser does it: password check, then the authorization-code step with PKCE, then the
     * callback. Fails the test when a step does not answer as expected.
     */
    public Session signIn(String email, String password) {
        Response password1 = signInPassword(email, password);
        require(password1.status() == 204, "sign-in answered " + password1.status());
        return completeSignIn();
    }

    /** The redirects after a successful password check. */
    public Session completeSignIn() {
        Response start = get("/api/v1/auth/start?continue=/");
        require(start.status() == 302, "start answered " + start.status());
        Response authorize = get(location(start));
        require(authorize.status() == 302, "authorize answered " + authorize.status() + " " + authorize.body());
        Response callback = get(location(authorize));
        require(callback.status() == 302, "callback answered " + callback.status());
        require(cookies.containsKey("platform_at"), "no access cookie was set");
        return new Session(cookies.get("platform_at"), cookies.get("platform_rt"));
    }

    /** The location header of a redirect, reduced to path and query (the browser stays on the same host). */
    public static String location(Response redirect) {
        String location = redirect.header("Location").orElseThrow(() -> new AssertionError("No Location header"));
        if (location.startsWith("/")) {
            return location;
        }
        URI uri = URI.create(location);
        return uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
    }

    /** The default headers, with any header the test names replacing the default of the same name. */
    private static String[] overriding(List<String> defaults, String... given) {
        Map<String, String[]> merged = new LinkedHashMap<>();
        for (int i = 0; i + 1 < defaults.size(); i += 2) {
            merged.put(defaults.get(i).toLowerCase(), new String[] {defaults.get(i), defaults.get(i + 1)});
        }
        for (int i = 0; i + 1 < given.length; i += 2) {
            merged.put(given[i].toLowerCase(), new String[] {given[i], given[i + 1]});
        }
        List<String> all = new ArrayList<>();
        merged.values().forEach(pair -> all.addAll(List.of(pair)));
        return all.toArray(String[]::new);
    }

    private void ensureCsrfCookie() {
        if (!cookies.containsKey("XSRF-TOKEN")) {
            get("/api/v1/auth/csrf");
        }
    }

    private String[] withBrowserHeaders(String... headers) {
        List<String> all = new ArrayList<>(List.of("Host", host, "X-Forwarded-For", source));
        if (!cookies.isEmpty()) {
            StringBuilder cookie = new StringBuilder();
            cookies.forEach((name, value) -> {
                if (!cookie.isEmpty()) {
                    cookie.append("; ");
                }
                cookie.append(name).append('=').append(value);
            });
            all.addAll(List.of("Cookie", cookie.toString()));
        }
        all.addAll(List.of(headers));
        return all.toArray(String[]::new);
    }

    private Response remember(Response response) {
        List<String> setCookies = response.headers().entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase("set-cookie"))
                .flatMap(entry -> entry.getValue().stream())
                .toList();
        for (String setCookie : setCookies) {
            String pair = setCookie.split(";", 2)[0];
            int equals = pair.indexOf('=');
            String name = pair.substring(0, equals);
            String value = pair.substring(equals + 1);
            boolean expired = setCookie.toLowerCase().contains("max-age=0") || value.isEmpty();
            if (expired) {
                cookies.remove(name);
            } else {
                cookies.put(name, value);
            }
        }
        return response;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
