package app.platform.publicapi.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.data.RecordsSupport;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations.Organization;
import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * What the tests of the public API share (Sprint 30): a caller that talks to the public interface the way another
 * program does, with a bearer token in a header and nothing else (no cookie, no forgery header), on the host of an
 * organization; and, beside it, the same person's browser, so a test can put the two doors side by side.
 */
abstract class PublicApiSupport extends RecordsSupport {

    protected static final String API = "/api/public/v1";
    protected static final String OBJECTS = API + "/objects";
    protected static final String EMPLOYEES_API = OBJECTS + "/Employee__c";
    protected static final String EMPLOYEE_RECORDS = EMPLOYEES_API + "/records";

    /**
     * Someone calling the public interface: a host, a token (or none), and the browser of the same person for the
     * internal interface.
     */
    protected final class Caller {

        private final TestHttp http;
        private final String host;
        private final String token;
        private final TestBrowser browser;
        private final String[] extra;
        private final String source = "2001:db8:" + Integer.toHexString(ThreadLocalRandom.current().nextInt(0x10000))
                + ":" + Integer.toHexString(ThreadLocalRandom.current().nextInt(0x10000)) + "::1";

        private final int onPort;

        Caller(int onPort, String host, String token, TestBrowser browser, String... extra) {
            this.onPort = onPort;
            this.http = new TestHttp(onPort);
            this.host = host;
            this.token = token;
            this.browser = browser;
            this.extra = extra.clone();
        }

        /** The same person on the internal interface. */
        TestBrowser browser() {
            return browser;
        }

        String token() {
            return token;
        }

        String host() {
            return host;
        }

        Response get(String path, String... headers) {
            return request("GET", path, null, headers);
        }

        Response post(String path, String json, String... headers) {
            return request("POST", path, json, headers);
        }

        Response patch(String path, String json, String... headers) {
            return request("PATCH", path, json, headers);
        }

        Response delete(String path, String... headers) {
            return request("DELETE", path, null, headers);
        }

        /** A request with the headers of a program: the host, the token, and the content type of a body. */
        Response request(String method, String path, String body, String... headers) {
            // A header the test names replaces the default of the same name: a request has one content type.
            java.util.Map<String, String[]> merged = new java.util.LinkedHashMap<>();
            List<String> given = new ArrayList<>(List.of("Host", host, "X-Forwarded-For", source));
            if (token != null) {
                given.addAll(List.of("Authorization", "Bearer " + token));
            }
            if (body != null) {
                given.addAll(List.of("Content-Type", "application/json"));
            }
            given.addAll(List.of(extra));
            given.addAll(List.of(headers));
            for (int index = 0; index + 1 < given.size(); index += 2) {
                merged.put(given.get(index).toLowerCase(), new String[] {given.get(index), given.get(index + 1)});
            }
            List<String> all = new ArrayList<>();
            merged.values().forEach(pair -> all.addAll(List.of(pair)));
            return http.request(method, path, body == null ? "" : body, all.toArray(String[]::new));
        }

        /** A request that carries a different host than the caller's own. */
        Caller onHost(String otherHost) {
            return new Caller(onPort, otherHost, token, browser, extra);
        }

        /** The same caller with these headers (name and value pairs) added to every request, for forging. */
        Caller withHeaders(String... pairs) {
            return new Caller(onPort, host, token, browser, pairs);
        }

        /** The same caller talking to another running instance of the application (same database, same tokens). */
        Caller onInstance(int otherPort) {
            return new Caller(otherPort, host, token, browser, extra);
        }

        /** The same caller with another token (or none). */
        Caller withToken(String otherToken) {
            return new Caller(onPort, host, otherToken, browser, extra);
        }
    }

    /** A caller signed in as the person; the token is the access token the sign-in left. */
    protected Caller caller(String host, TestBrowser signedIn) {
        return new Caller(port, host, signedIn.cookie("platform_at").orElseThrow(), signedIn);
    }

    /** The administrator of the organization, who may do everything with every object. */
    protected Caller administrator(Organization organization) {
        return caller(organization.host(), adminOf(organization));
    }

    /** A member who holds exactly these actions on {@code Employee__c} and may edit its fields. */
    protected Caller memberWith(Organization organization, TestBrowser admin, String... actions) {
        return caller(organization.host(), as(organization, memberWithActions(organization, admin, actions)));
    }

    /** A caller with no token. */
    protected Caller anonymous(String host) {
        return new Caller(port, host, null, null);
    }

    // ---- reading answers ----

    protected static String id(Response response) {
        return JsonPath.read(response.body(), "$.data.id");
    }

    protected static long version(Response response) {
        return JsonPath.<Number>read(response.body(), "$.data.version").longValue();
    }

    protected static String code(Response response) {
        return JsonPath.read(response.body(), "$.error.code");
    }

    protected static String etag(Response response) {
        return response.header("ETag").orElseThrow(() -> new AssertionError("no ETag"));
    }

    protected static String createBody(String fieldsJson) {
        return "{\"fields\":" + fieldsJson + "}";
    }

    /** Creates an employee through the public interface and returns its identifier. */
    protected String newEmployee(Caller caller, String name, String department) {
        Response created = caller.post(EMPLOYEE_RECORDS, createBody("{\"name__c\":\"" + name
                + "\",\"department__c\":\"" + department + "\"}"));
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        return id(created);
    }

    /** A fresh key for a keyed write. */
    protected static String key() {
        return "key-" + UUID.randomUUID();
    }

    /** The identifiers a query returns, following the cursor to the end. */
    protected static List<String> queried(Caller caller, String object, String bodyWithoutCursor, int pageSize) {
        List<String> ids = new ArrayList<>();
        String cursor = null;
        do {
            String body = bodyWithoutCursor.substring(0, bodyWithoutCursor.length() - 1)
                    + (bodyWithoutCursor.length() > 2 ? "," : "") + "\"limit\":" + pageSize
                    + (cursor == null ? "" : ",\"cursor\":\"" + cursor + "\"") + "}";
            Response page = caller.post(OBJECTS + "/" + object + "/records/query", body);
            assertThat(page.status()).as(page.body()).isEqualTo(200);
            ids.addAll(JsonPath.<List<String>>read(page.body(), "$.data[*].id"));
            cursor = JsonPath.<Boolean>read(page.body(), "$.pagination.hasMore")
                    ? JsonPath.<String>read(page.body(), "$.pagination.nextCursor") : null;
        } while (cursor != null);
        return ids;
    }
}
