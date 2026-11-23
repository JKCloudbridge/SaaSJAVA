package app.platform.publicapi.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.licensing.Plans;
import app.platform.licensing.Subscriptions;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations;
import app.platform.testsupport.TestOrganizations.Member;
import app.platform.testsupport.TestOrganizations.Organization;
import app.platform.testsupport.TestPlatform;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * What the tests of connected applications share (Sprint 31): an organization on a plan that has the feature, members
 * who may manage applications, the short ways to register one through the real management API, to ask the token
 * endpoint for a token with Basic authentication, and a {@code Caller} that talks to the public interface with the
 * token of an application, the way another program does. Names are generic.
 */
abstract class ClientSupport extends PublicApiSupport {

    protected static final String CLIENTS = "/api/v1/connected-apps";
    protected static final String TOKEN = "/api/v1/oauth2/token";
    protected static final String FEATURE = "integrations";
    protected static final String ALL_SCOPES = "[\"objects.read\",\"records.read\",\"records.write\"]";

    @Autowired
    private Plans clientPlans;

    @Autowired
    private Subscriptions clientSubscriptions;

    /** What registering an application answered: the identifiers and the secret that is shown once. */
    protected record Registered(String id, String clientId, String secret, Response response) {

        /** The same application's identifiers without the secret, for a test that must never print it. */
        @Override
        public String toString() {
            return "Registered[" + id + "]";
        }
    }

    /** An organization on a plan that has the feature {@code integrations}, with an {@code Employee__c} object. */
    protected Organization organizationWithClients() {
        Organization organization = TestOrganizations.create(users);
        TestPlatform.subscribe(clientPlans, clientSubscriptions, organization.id(), 8, 3, FEATURE);
        makeEmployeeObject(adminOf(organization));
        return organization;
    }

    /** An organization on a plan that has no feature at all. */
    protected Organization organizationWithoutTheFeature() {
        Organization organization = TestOrganizations.create(users);
        TestPlatform.subscribe(clientPlans, clientSubscriptions, organization.id(), 8, 3);
        makeEmployeeObject(adminOf(organization));
        return organization;
    }

    /**
     * A member who holds exactly these abilities and this data-access matrix (a JSON object with {@code objects} and
     * {@code fields}, or null for none).
     */
    protected Integrator memberWithAbilitiesAndMatrix(Organization organization, TestBrowser admin, String matrixJson,
            String... abilities) {
        Member person = TestOrganizations.join(users, organization.tenant(), false);
        String listed = String.join(",", Arrays.stream(abilities).map(name -> "\"" + name + "\"").toList());
        Response profile = admin.postJson("/api/v1/profiles", "{\"name\":\"profile-" + UUID.randomUUID() + "\","
                + "\"description\":\"\",\"licenceType\":\"user\",\"abilities\":[" + listed + "]}");
        assertThat(profile.status()).as(profile.body()).isEqualTo(201);
        String profileId = JsonPath.read(profile.body(), "$.data.id");
        Response set = admin.request("PUT", "/api/v1/members/" + person.membership() + "/profile",
                "{\"profileId\":\"" + profileId + "\"}");
        assertThat(set.status()).as(set.body()).isIn(200, 204);
        if (matrixJson != null) {
            Response matrix = admin.request("PUT", "/api/v1/profiles/" + profileId + "/data-access", matrixJson);
            assertThat(matrix.status()).as(matrix.body()).isEqualTo(200);
        }
        return new Integrator(person, profileId);
    }

    /** A member and the profile that gives them their rights, to change those rights later. */
    protected record Integrator(Member member, String profileId) {
    }

    /** Changes the whole data-access matrix of the profile (a JSON object with {@code objects} and {@code fields}). */
    protected static void setMatrix(TestBrowser admin, Integrator who, String matrixJson) {
        Response matrix = admin.request("PUT", "/api/v1/profiles/" + who.profileId() + "/data-access", matrixJson);
        assertThat(matrix.status()).as(matrix.body()).isEqualTo(200);
    }

    /** The matrix that gives exactly these actions on {@code Employee__c} and every field of it. */
    protected static String employeeMatrix(String... actions) {
        return "{\"objects\":[{\"key\":\"Employee__c\",\"actions\":["
                + String.join(",", Arrays.stream(actions).map(action -> "\"" + action + "\"").toList())
                + "]}],\"fields\":" + ALL_EMPLOYEE_FIELDS + "}";
    }

    /** A member who may manage connected applications and holds exactly these actions on {@code Employee__c}. */
    protected Integrator integrationMember(Organization organization, TestBrowser admin, String... actions) {
        String matrix = actions.length == 0 ? null : employeeMatrix(actions);
        return memberWithAbilitiesAndMatrix(organization, admin, matrix, "clients.view", "clients.manage");
    }

    // ---- the management API ----

    protected static String registerBody(String name, String scopesJson, String objectsJson, Integer minutes) {
        return "{\"name\":\"" + name + "\",\"description\":\"for tests\",\"scopes\":" + scopesJson
                + (objectsJson == null ? "" : ",\"objects\":" + objectsJson)
                + (minutes == null ? "" : ",\"tokenMinutes\":" + minutes) + "}";
    }

    /** Registers an application as the person behind the browser; the registration must succeed. */
    protected Registered register(TestBrowser browser, String name, String scopesJson, String objectsJson) {
        Response response = browser.postJson(CLIENTS, registerBody(name, scopesJson, objectsJson, null));
        assertThat(response.status()).as(response.body()).isEqualTo(201);
        return new Registered(JsonPath.read(response.body(), "$.data.detail.app.id"),
                JsonPath.read(response.body(), "$.data.detail.app.clientId"),
                JsonPath.read(response.body(), "$.data.clientSecret"), response);
    }

    protected Registered register(TestBrowser browser, String scopesJson) {
        return register(browser, "app-" + UUID.randomUUID().toString().substring(0, 8), scopesJson, null);
    }

    protected static Response read(TestBrowser browser, String id) {
        return browser.get(CLIENTS + "/" + id);
    }

    protected static Response disable(TestBrowser browser, String id) {
        return browser.postJson(CLIENTS + "/" + id + "/disable", "{}");
    }

    protected static Response enable(TestBrowser browser, String id) {
        return browser.postJson(CLIENTS + "/" + id + "/enable", "{}");
    }

    protected static Response rotate(TestBrowser browser, String id, int overlapHours, boolean endTokensNow) {
        return browser.postJson(CLIENTS + "/" + id + "/secrets/rotate",
                "{\"overlapHours\":" + overlapHours + ",\"endTokensNow\":" + endTokensNow + "}");
    }

    protected static Response revokeSecret(TestBrowser browser, String id, String secretId) {
        return browser.request("DELETE", CLIENTS + "/" + id + "/secrets/" + secretId, "");
    }

    protected static Response removeApplication(TestBrowser browser, String id) {
        return browser.request("DELETE", CLIENTS + "/" + id, "");
    }

    protected static long versionOfApplication(TestBrowser browser, String id) {
        return JsonPath.<Number>read(read(browser, id).body(), "$.data.app.version").longValue();
    }

    protected static List<String> secretIds(TestBrowser browser, String id) {
        return JsonPath.read(read(browser, id).body(), "$.data.secrets[*].id");
    }

    protected static String newSecretOf(Response rotation) {
        return JsonPath.read(rotation.body(), "$.data.clientSecret");
    }

    // ---- the token endpoint ----

    /** A request for a token with Basic authentication; the source address is the test's own unless one is given. */
    protected Response tokenRequest(String host, String clientId, String secret, String scope, String source) {
        String basic = Base64.getEncoder().encodeToString((clientId + ":" + secret).getBytes(StandardCharsets.UTF_8));
        String form = "grant_type=client_credentials" + (scope == null ? "" : "&scope=" + scope.replace(" ", "%20"));
        return new TestHttp(port).post(TOKEN, form, "Host", host, "X-Forwarded-For", source,
                "Authorization", "Basic " + basic, "Content-Type", "application/x-www-form-urlencoded");
    }

    protected Response tokenRequest(String host, String clientId, String secret, String scope) {
        return tokenRequest(host, clientId, secret, scope, freshSource());
    }

    /** A request that sends the client identifier and secret in the form body instead of the header. */
    protected Response tokenRequestInBody(String host, String clientId, String secret) {
        String form = "grant_type=client_credentials&client_id=" + clientId + "&client_secret=" + secret;
        return new TestHttp(port).post(TOKEN, form, "Host", host, "X-Forwarded-For", freshSource(),
                "Content-Type", "application/x-www-form-urlencoded");
    }

    protected static String freshSource() {
        return "2001:db8:" + Integer.toHexString(ThreadLocalRandom.current().nextInt(0x10000)) + ":"
                + Integer.toHexString(ThreadLocalRandom.current().nextInt(0x10000)) + "::1";
    }

    protected static String accessToken(Response token) {
        assertThat(token.status()).as(token.body()).isEqualTo(200);
        return JsonPath.read(token.body(), "$.access_token");
    }

    /** The application, signed in with its secret: a program that talks to the public interface. */
    protected Caller clientCaller(Organization organization, Registered app, String scope) {
        Response token = tokenRequest(organization.host(), app.clientId(), app.secret(), scope);
        return new Caller(port, organization.host(), accessToken(token), null);
    }

    protected Caller clientCaller(Organization organization, Registered app) {
        return clientCaller(organization, app, null);
    }

    /** The one body every refusal of the token endpoint for a client has, whatever the cause. */
    protected static String invalidClientBody() {
        return "{\"error\":\"invalid_client\",\"error_description\":\"Client authentication failed.\"}";
    }

    protected static void assertInvalidClient(Response answer) {
        assertThat(answer.status()).as(answer.body()).isEqualTo(401);
        assertThat(answer.body()).isEqualTo(invalidClientBody());
    }
}
