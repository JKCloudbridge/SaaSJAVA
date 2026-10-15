package app.platform.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.platform.testsupport.IdentityDb;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations;
import app.platform.testsupport.TestOrganizations.Organization;
import app.platform.testsupport.TestSignIn;
import app.platform.testsupport.TestUsers;
import com.jayway.jsonpath.JsonPath;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The access token of a custom page's script and the cross-origin rule it needs (Sprint 18, ADR-0127): a member asks
 * for it with their own session, it works as a bearer token for the data interface and for nothing else, it ends with
 * the sign-in it came from and in a few minutes, it belongs to one organization, it is stored only as a hash, and the
 * only cross-origin caller the API answers is a sandboxed frame without credentials.
 */
@PlatformIntegrationTest
class EmbeddedTokenIT {

    private static final String TOKENS = "/api/v1/embedded-tokens";
    private static final String ME = "/api/v1/auth/me";

    @LocalServerPort
    private int port;

    @Autowired
    private Users users;

    private Organization organization() {
        return TestOrganizations.create(users);
    }

    private TestBrowser signedIn(Organization organization) {
        return TestOrganizations.signedIn(port, organization.host(), organization.admin().person());
    }

    private Response bearer(String host, String token, String method, String path, String... more) {
        String[] headers = new String[more.length + 4];
        headers[0] = "Host";
        headers[1] = host;
        headers[2] = "Authorization";
        headers[3] = "Bearer " + token;
        System.arraycopy(more, 0, headers, 4, more.length);
        return new TestHttp(port).request(method, path, null, headers);
    }

    private static String tokenOf(Response issued) {
        assertThat(issued.status()).as(issued.body()).isEqualTo(201);
        return JsonPath.read(issued.body(), "$.data.token");
    }

    // ---- asking for it ----

    @Test
    void aMemberAsksWithTheirOwnSessionAndGetsAShortLivedTokenThatWorksAsABearer() {
        Organization organization = organization();
        TestBrowser browser = signedIn(organization);

        Response issued = browser.postJson(TOKENS, "{\"customPage\":\"Inspection__c\"}");
        String token = tokenOf(issued);

        assertThat(token).startsWith("pcs_");
        assertThat(issued.header("Cache-Control")).hasValueSatisfying(value -> assertThat(value).contains("no-store"));
        Instant ends = Instant.parse(JsonPath.read(issued.body(), "$.data.expiresAt"));
        assertThat(Duration.between(Instant.now(), ends)).isBetween(Duration.ofMinutes(4), Duration.ofMinutes(6));
        Response me = bearer(organization.host(), token, "GET", ME);
        assertThat(me.status()).as(me.body()).isEqualTo(200);
        assertThat(JsonPath.<String>read(me.body(), "$.data.email"))
                .isEqualTo(organization.admin().person().email());
    }

    @Test
    void askingWithoutABodyIsAllowedAndABadPageNameIsNot() {
        TestBrowser browser = signedIn(organization());

        assertThat(browser.post(TOKENS).status()).isEqualTo(201);
        assertThat(browser.postJson(TOKENS, "{\"customPage\":\"not a name\"}").status()).isEqualTo(400);
    }

    @Test
    void thePlatformHostGivesNoTokenAndAnUnknownCallerGetsNone() {
        TestBrowser platform = TestOrganizations.signedIn(port, TestSignIn.PLATFORM_HOST,
                TestUsers.create(users));

        assertThat(platform.post(TOKENS).status()).isEqualTo(404);
        assertThat(new TestHttp(port).request("POST", TOKENS, null, "Host", organization().host()).status())
                .as("no sign-in, no token (the forgery check may answer first)").isIn(401, 403);
    }

    @Test
    void aTokenOfAScriptCannotAskForAnother() {
        Organization organization = organization();
        String token = tokenOf(signedIn(organization).post(TOKENS));

        Response again = bearer(organization.host(), token, "POST", TOKENS);

        assertThat(again.status()).isEqualTo(403);
    }

    // ---- what it reaches ----

    @Test
    void itReachesTheDataInterfaceAndNothingElse() {
        Organization organization = organization();
        String token = tokenOf(signedIn(organization).post(TOKENS));
        String host = organization.host();

        for (String path : List.of("/api/v1/auth/me", "/api/v1/applications", "/api/v1/tenant/current",
                "/api/v1/data-catalogue", "/api/v1/metadata/objects", "/api/v1/metadata/field-types")) {
            assertThat(bearer(host, token, "GET", path).status()).as("GET " + path).isEqualTo(200);
        }
        // An object that does not exist is "not found": the policy let the call through to the data interface.
        assertThat(bearer(host, token, "GET", "/api/v1/objects/Nothing__c/records").status()).isEqualTo(404);
        assertThat(bearer(host, token, "POST", "/api/v1/objects/Nothing__c/records").status()).isIn(400, 404, 415);

        for (String[] refused : new String[][] {
                {"GET", "/api/v1/members"}, {"GET", "/api/v1/profiles"}, {"GET", "/api/v1/access-policies"},
                {"GET", "/api/v1/groups"}, {"GET", "/api/v1/sharing/objects"}, {"GET", "/api/v1/audit-events"},
                {"GET", "/api/v1/metadata/change-sets"}, {"GET", "/api/v1/metadata/releases"},
                {"GET", "/api/v1/metadata/applications"}, {"POST", "/api/v1/metadata/objects"},
                {"PUT", "/api/v1/metadata/objects/Deal__c"}, {"DELETE", "/api/v1/metadata/objects/Deal__c"},
                {"POST", "/api/v1/metadata/custom-page-assets"}, {"POST", "/api/v1/auth/sign-out"},
                {"POST", "/api/v1/auth/refresh"}, {"POST", "/api/v1/auth/sign-out-all"},
                {"POST", "/api/v1/auth/password"}, {"POST", "/api/v1/invitations"},
                {"POST", "/api/v1/organization/leave"}, {"GET", "/api/v1/licences"},
                {"GET", "/api/v1/platform/status"}, {"GET", "/api/v1/applications/custom-pages/Inspection__c"}}) {
            Response answer = bearer(host, token, refused[0], refused[1]);
            assertThat(answer.status()).as(refused[0] + " " + refused[1] + " " + answer.body())
                    .isIn(400, 401, 403, 404);
            assertThat(answer.status()).as("not an acceptance: " + refused[1]).isNotEqualTo(200).isNotEqualTo(201)
                    .isNotEqualTo(204);
        }
        assertThat(bearer(host, token, "GET", "/api/v1/members").status()).isEqualTo(403);
        assertThat(JsonPath.<String>read(bearer(host, token, "GET", "/api/v1/members").body(), "$.error.code"))
                .isEqualTo("FORBIDDEN");
    }

    @Test
    void theViewersOwnAbilitiesDoNotWidenIt() {
        // The administrator can read the member list with a normal token and cannot with the token of a script.
        Organization organization = organization();
        TestBrowser browser = signedIn(organization);
        String token = tokenOf(browser.post(TOKENS));

        assertThat(browser.get("/api/v1/members").status()).isEqualTo(200);
        assertThat(bearer(organization.host(), token, "GET", "/api/v1/members").status()).isEqualTo(403);
    }

    // ---- how long it lives and what it hangs on ----

    @Test
    void itEndsWithTheSignInItCameFromAndInAFewMinutes() throws SQLException {
        Organization organization = organization();
        TestBrowser browser = signedIn(organization);
        String ending = tokenOf(browser.post(TOKENS));
        String expiring = tokenOf(browser.post(TOKENS));
        IdentityDb.executeWithoutTriggers("update embedded_token set issued_at = now() - interval '10 minutes', "
                + "expires_at = now() - interval '5 minutes' where user_id = ? and token_hash = ?",
                organization.admin().person().user().id(), "sha256:" + sha256(expiring));

        assertThat(bearer(organization.host(), expiring, "GET", ME).status()).as("past its own end").isEqualTo(401);
        assertThat(bearer(organization.host(), ending, "GET", ME).status()).isEqualTo(200);
        assertThat(browser.post("/api/v1/auth/sign-out").status()).isIn(200, 204);

        assertThat(bearer(organization.host(), ending, "GET", ME).status()).as("signed out").isEqualTo(401);
    }

    @Test
    void itBelongsToTheOrganizationOfTheHostItWasAskedOn() {
        Organization first = organization();
        Organization second = organization();
        String token = tokenOf(signedIn(first).post(TOKENS));

        assertThat(bearer(first.host(), token, "GET", ME).status()).isEqualTo(200);
        assertThat(bearer(second.host(), token, "GET", ME).status()).isEqualTo(401);
        assertThat(bearer(TestSignIn.PLATFORM_HOST, token, "GET", ME).status()).isEqualTo(401);
    }

    @Test
    void itNeverWorksAfterTheMembershipEnds() throws SQLException {
        Organization organization = organization();
        TestBrowser browser = signedIn(organization);
        String token = tokenOf(browser.post(TOKENS));
        assertThat(bearer(organization.host(), token, "GET", ME).status()).isEqualTo(200);

        IdentityDb.execute("update oauth2_authorization set revoked_at = now(), revoked_reason = 'test', "
                + "version = version + 1, updated_by = user_id where user_id = ?",
                organization.admin().person().user().id());

        assertThat(bearer(organization.host(), token, "GET", ME).status()).isEqualTo(401);
    }

    @Test
    void onlyTheHashOfTheTokenIsStoredAndTheDatabaseKeepsItShort() throws SQLException {
        Organization organization = organization();
        UUID user = organization.admin().person().user().id();
        String token = tokenOf(signedIn(organization).post(TOKENS));

        List<String> hashes = IdentityDb.strings("select token_hash from embedded_token where user_id = ?", user);
        String everything = IdentityDb.strings("select t::text from embedded_token t").toString();

        assertThat(hashes).hasSize(1);
        assertThat(hashes.get(0)).startsWith("sha256:").doesNotContain(token);
        assertThat(everything).doesNotContain(token);
        assertThatThrownBy(() -> IdentityDb.executeWithoutTriggers("insert into embedded_token (authorization_id, "
                + "user_id, bound_tenant_id, token_hash, issued_at, expires_at, created_by, updated_by) "
                + "select id, user_id, bound_tenant_id, 'sha256:' || repeat('a', 64), now(), "
                + "now() + interval '20 minutes', user_id, user_id from oauth2_authorization where user_id = ? "
                + "limit 1", user)).hasMessageContaining("embedded_token_short_lived");
    }

    @Test
    void theIssueIsAuditedByPageNameAndTheTokenAppearsNowhereInTheAuditTrail() {
        Organization organization = organization();
        UUID user = organization.admin().person().user().id();

        String token = tokenOf(signedIn(organization).postJson(TOKENS, "{\"customPage\":\"Inspection__c\"}"));

        assertThat(IdentityDb.auditOfType("auth.embedded.issued"))
                .anySatisfy(record -> assertThat(record.attributes()).contains("Inspection__c"));
        assertThat(IdentityDb.entireAuditTableAsText()).doesNotContain(token);
    }

    // ---- the cross-origin rule ----

    @Test
    void aSandboxedFrameMayAskFirstAndIsAnsweredWithoutCredentials() {
        Organization organization = organization();

        Response preflight = new TestHttp(port).request("OPTIONS", "/api/v1/objects/Item__c/records", null,
                "Host", organization.host(), "Origin", "null", "Access-Control-Request-Method", "GET",
                "Access-Control-Request-Headers", "authorization");

        assertThat(preflight.status()).as(preflight.body()).isIn(200, 204);
        assertThat(preflight.header("Access-Control-Allow-Origin")).hasValue("null");
        assertThat(preflight.header("Access-Control-Allow-Credentials")).isEmpty();
        assertThat(preflight.header("Access-Control-Allow-Headers")).hasValueSatisfying(
                value -> assertThat(value.toLowerCase()).contains("authorization"));
        assertThat(preflight.header("Access-Control-Max-Age")).hasValue("600");
    }

    @Test
    void noOtherOriginAndNoOtherHeaderIsAllowed() {
        Organization organization = organization();
        TestHttp http = new TestHttp(port);

        Response foreign = http.request("OPTIONS", "/api/v1/objects/Item__c/records", null, "Host",
                organization.host(), "Origin", "https://other.example.test", "Access-Control-Request-Method", "GET");
        Response unwanted = http.request("OPTIONS", "/api/v1/objects/Item__c/records", null, "Host",
                organization.host(), "Origin", "null", "Access-Control-Request-Method", "GET",
                "Access-Control-Request-Headers", "x-something-else");
        Response unwantedMethod = http.request("OPTIONS", "/api/v1/objects/Item__c/records", null, "Host",
                organization.host(), "Origin", "null", "Access-Control-Request-Method", "TRACE");

        assertThat(foreign.header("Access-Control-Allow-Origin")).isEmpty();
        assertThat(unwanted.header("Access-Control-Allow-Origin")).isEmpty();
        assertThat(unwantedMethod.header("Access-Control-Allow-Origin")).isEmpty();
    }

    @Test
    void theAnswerToASandboxedFrameCarriesTheAllowedOriginAndNeverCredentials() {
        Organization organization = organization();
        String token = tokenOf(signedIn(organization).post(TOKENS));

        Response answer = bearer(organization.host(), token, "GET", ME, "Origin", "null");

        assertThat(answer.status()).isEqualTo(200);
        assertThat(answer.header("Access-Control-Allow-Origin")).hasValue("null");
        assertThat(answer.header("Access-Control-Allow-Credentials")).isEmpty();
        assertThat(answer.header("Vary")).hasValueSatisfying(value -> assertThat(value).contains("Origin"));
    }

    @Test
    void aRequestFromASandboxedFrameNeverGetsItsSessionCookieTurnedIntoAToken() {
        Organization organization = organization();
        TestBrowser browser = signedIn(organization);

        Response plain = browser.get(ME);
        Response fromFrame = browser.get(ME, "Origin", "null");

        assertThat(plain.status()).isEqualTo(200);
        assertThat(fromFrame.status()).as("the cookie is ignored for a frame without its own token").isEqualTo(401);
    }

    @Test
    void theApplicationsOwnPagesAreNotMistakenForCrossOriginCallers() {
        // Behind the development proxy the page's origin differs from the host the API sees; that must still work.
        Organization organization = organization();
        TestBrowser browser = signedIn(organization);

        Response get = browser.get(ME, "Origin", "http://" + organization.host() + ":3000");
        Response post = browser.post(TOKENS, "Origin", "http://" + organization.host() + ":3000");

        assertThat(get.status()).isEqualTo(200);
        assertThat(post.status()).isEqualTo(201);
        assertThat(get.header("Access-Control-Allow-Origin")).isEmpty();
    }

    private static String sha256(String text) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
