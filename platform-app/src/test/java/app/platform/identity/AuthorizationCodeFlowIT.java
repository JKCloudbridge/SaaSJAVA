package app.platform.identity;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestMembers;
import app.platform.testsupport.TestSignIn;
import app.platform.testsupport.TestUsers;
import app.platform.testsupport.TestUsers.TestUser;
import app.platform.testsupport.tenancy.TenantFixtures;
import com.jayway.jsonpath.JsonPath;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jwt.SignedJWT;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The authorization-code flow with PKCE as the standard endpoints expose it (stories S3-SEC-18 and S3-SEC-20): PKCE is
 * required, the code can only go back to this host's callback, the code works once, the state is checked, the page the
 * browser lands on cannot be steered to another site, and the ID token is signed with a published key.
 */
@PlatformIntegrationTest
class AuthorizationCodeFlowIT {

    private static final String CLIENT = "platform-web";

    @LocalServerPort
    private int port;

    @Autowired
    private Users users;

    private static String enc(String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8);
    }

    private static String challengeOf(String verifier) throws Exception {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
    }

    private static String callback(String host) {
        return "http://" + host + "/api/v1/auth/callback";
    }

    private static String authorize(String redirect, String challenge, String method) {
        return "/api/v1/oauth2/authorize?response_type=code&client_id=" + CLIENT + "&scope=openid&state=abc"
                + "&redirect_uri=" + enc(redirect)
                + (challenge == null ? "" : "&code_challenge=" + enc(challenge))
                + (method == null ? "" : "&code_challenge_method=" + method);
    }

    private TestBrowser signedInWithPassword(String host, TestUser user) {
        TestMembers.addForHost(host, user.user().id());
        TestBrowser browser = new TestBrowser(port, host);
        assertThat(browser.signInPassword(user.email(), user.password()).status()).isEqualTo(204);
        return browser;
    }

    private Response token(String host, String code, String verifier, String redirect) {
        String form = "grant_type=authorization_code&client_id=" + CLIENT + "&code=" + enc(code)
                + "&redirect_uri=" + enc(redirect) + "&code_verifier=" + enc(verifier);
        return new TestHttp(port).post("/api/v1/oauth2/token", form, "Host", host,
                "Content-Type", "application/x-www-form-urlencoded");
    }

    private static String codeOf(Response redirect) {
        String location = redirect.header("Location").orElseThrow(() -> new AssertionError("no redirect"));
        String query = URI.create(location).getRawQuery();
        for (String pair : query.split("&")) {
            if (pair.startsWith("code=")) {
                return pair.substring(5);
            }
        }
        throw new AssertionError("no code in " + location);
    }

    // ---- PKCE ----

    @Test
    void aRequestWithoutAProofKeyIsRefusedAndNoCodeIsIssued() {
        TestUser user = TestUsers.create(users);
        TestBrowser browser = signedInWithPassword(TestSignIn.PLATFORM_HOST, user);

        Response response = browser.get(authorize(callback(TestSignIn.PLATFORM_HOST), null, null));

        assertThat(response.header("Location").orElse("")).doesNotContain("code=");
        assertThat(response.status()).isIn(302, 400);
        assertThat(response.header("Location").orElse("error=invalid_request")).contains("error=invalid_request");
    }

    @Test
    void thePlainChallengeMethodIsRefusedBecauseItProvesNothing() throws Exception {
        TestUser user = TestUsers.create(users);
        TestBrowser browser = signedInWithPassword(TestSignIn.PLATFORM_HOST, user);

        Response response = browser.get(authorize(callback(TestSignIn.PLATFORM_HOST),
                "plain-verifier-value-123456789012345678901234", "plain"));

        assertThat(response.header("Location").orElse("")).doesNotContain("code=");
    }

    @Test
    void aCodeIsRedeemedOnlyWithTheVerifierThatMatchesItsChallenge() throws Exception {
        TestUser user = TestUsers.create(users);
        String host = TestSignIn.PLATFORM_HOST;
        TestBrowser browser = signedInWithPassword(host, user);
        String verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(UUID.randomUUID().toString()
                .repeat(2).getBytes(StandardCharsets.US_ASCII));
        Response authorized = browser.get(authorize(callback(host), challengeOf(verifier), "S256"));
        assertThat(authorized.status()).isEqualTo(302);
        String code = codeOf(authorized);

        Response wrong = token(host, code, verifier + "tampered", callback(host));

        assertThat(wrong.status()).isEqualTo(400);
        assertThat(JsonPath.<String>read(wrong.body(), "$.error")).isEqualTo("invalid_grant");
    }

    @Test
    void aCodeWorksOnceAndReplayingItEndsTheTokensItProduced() throws Exception {
        TestUser user = TestUsers.create(users);
        String host = TestSignIn.PLATFORM_HOST;
        TestBrowser browser = signedInWithPassword(host, user);
        String verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(UUID.randomUUID().toString()
                .repeat(2).getBytes(StandardCharsets.US_ASCII));
        String code = codeOf(browser.get(authorize(callback(host), challengeOf(verifier), "S256")));

        Response first = token(host, code, verifier, callback(host));
        assertThat(first.status()).isEqualTo(200);
        String access = JsonPath.read(first.body(), "$.access_token");
        assertThat(new TestHttp(port).get("/api/v1/auth/me", "Host", host, "Authorization", "Bearer " + access)
                .status()).isEqualTo(200);

        Response replay = token(host, code, verifier, callback(host));

        assertThat(replay.status()).isEqualTo(400);
        assertThat(new TestHttp(port).get("/api/v1/auth/me", "Host", host, "Authorization", "Bearer " + access)
                .status()).as("the tokens of a replayed code are revoked").isEqualTo(401);
    }

    @Test
    void thePublicTokenEndpointIssuesAnOpaqueAccessTokenAndASignedIdTokenButNoRefreshToken() throws Exception {
        TestUser user = TestUsers.create(users);
        String host = TestSignIn.PLATFORM_HOST;
        TestBrowser browser = signedInWithPassword(host, user);
        String verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(UUID.randomUUID().toString()
                .repeat(2).getBytes(StandardCharsets.US_ASCII));
        String code = codeOf(browser.get(authorize(callback(host), challengeOf(verifier), "S256")));

        Response response = token(host, code, verifier, callback(host));

        assertThat(response.status()).isEqualTo(200);
        assertThat(JsonPath.<String>read(response.body(), "$.token_type")).isEqualToIgnoringCase("bearer");
        assertThat(JsonPath.<String>read(response.body(), "$.access_token")).doesNotContain(".");
        assertThat(JsonPath.<Map<String, Object>>read(response.body(), "$")).doesNotContainKey("refresh_token");
        assertThat(JsonPath.<Integer>read(response.body(), "$.expires_in")).isBetween(500, 600);

        SignedJWT idToken = SignedJWT.parse(JsonPath.read(response.body(), "$.id_token"));
        Response jwks = new TestHttp(port).get("/api/v1/oauth2/jwks", "Host", host);
        assertThat(jwks.status()).isEqualTo(200);
        assertThat(jwks.body()).doesNotContain("\"d\"");
        ECKey key = (ECKey) JWKSet.parse(jwks.body()).getKeyByKeyId(idToken.getHeader().getKeyID());
        assertThat(key).as("the token names a published key").isNotNull();
        assertThat(idToken.verify(new ECDSAVerifier(key))).isTrue();
        assertThat(idToken.getJWTClaimsSet().getSubject()).isEqualTo(user.user().id().toString());
        assertThat(idToken.getJWTClaimsSet().getAudience()).containsExactly(CLIENT);
    }

    // ---- where the code may go ----

    @Test
    void theCodeCanNotBeSentToAnotherHostAnotherPathOrWithExtraParts() throws Exception {
        TestUser user = TestUsers.create(users);
        String host = TenantFixtures.createActiveTenant().host();
        String otherHost = TenantFixtures.createActiveTenant().host();
        TestBrowser browser = signedInWithPassword(host, user);
        String challenge = challengeOf("a-verifier-of-sufficient-length-for-the-test-123456");

        for (String redirect : List.of(
                callback(otherHost),
                "http://evil.example.test/api/v1/auth/callback",
                "http://" + host + "/other",
                "http://" + host + "/api/v1/auth/callback?next=https://evil.example.test",
                "http://user:pw@" + host + "/api/v1/auth/callback",
                "https://" + host + "/api/v1/auth/callback")) {
            Response response = browser.get(authorize(redirect, challenge, "S256"));

            assertThat(response.header("Location").orElse("")).as(redirect).doesNotContain("code=");
            assertThat(response.status()).as(redirect).isEqualTo(400);
        }
    }

    @Test
    void theAuthorizationEndpointWithoutASignInSendsTheBrowserToTheSignInPage() throws Exception {
        TestBrowser nobody = new TestBrowser(port, TestSignIn.PLATFORM_HOST);

        Response response = nobody.get(authorize(callback(TestSignIn.PLATFORM_HOST),
                challengeOf("a-verifier-of-sufficient-length-for-the-test-123456"), "S256"));

        assertThat(response.status()).isEqualTo(302);
        assertThat(response.header("Location")).hasValue("/sign-in");
    }

    @Test
    void anUnknownClientIsRefused() throws Exception {
        TestUser user = TestUsers.create(users);
        TestBrowser browser = signedInWithPassword(TestSignIn.PLATFORM_HOST, user);

        Response response = browser.get(authorize(callback(TestSignIn.PLATFORM_HOST),
                challengeOf("a-verifier-of-sufficient-length-for-the-test-123456"), "S256")
                .replace("client_id=" + CLIENT, "client_id=some-other-client"));

        assertThat(response.header("Location").orElse("")).doesNotContain("code=");
    }

    // ---- the return to the platform's callback ----

    @Test
    void theCallbackRefusesAStateThatIsNotTheOneTheBrowserStartedWith() {
        TestUser user = TestUsers.create(users);
        TestBrowser browser = signedInWithPassword(TestSignIn.PLATFORM_HOST, user);
        Response start = browser.get("/api/v1/auth/start?continue=/");
        Response authorize = browser.get(TestBrowser.location(start));
        String forged = TestBrowser.location(authorize).replaceAll("state=[^&]*", "state=attacker-chosen-state");

        Response callback = browser.get(forged);

        assertThat(callback.status()).isEqualTo(302);
        assertThat(callback.header("Location")).hasValue("/sign-in?problem=sign-in");
        assertThat(browser.cookie("platform_at")).isEmpty();
    }

    @Test
    void theCallbackWithoutTheCookieOfTheStartingBrowserOrWithAnErrorIssuesNothing() {
        TestUser user = TestUsers.create(users);
        TestBrowser browser = signedInWithPassword(TestSignIn.PLATFORM_HOST, user);
        Response start = browser.get("/api/v1/auth/start?continue=/");
        Response authorize = browser.get(TestBrowser.location(start));
        String location = TestBrowser.location(authorize);

        TestBrowser otherBrowser = new TestBrowser(port, TestSignIn.PLATFORM_HOST);
        Response withoutCookie = otherBrowser.get(location);
        Response withError = browser.get("/api/v1/auth/callback?error=access_denied&state=x");

        assertThat(withoutCookie.header("Location")).hasValue("/sign-in?problem=sign-in");
        assertThat(otherBrowser.cookie("platform_at")).isEmpty();
        assertThat(withError.header("Location")).hasValue("/sign-in?problem=sign-in");
        assertThat(browser.cookie("platform_at")).isEmpty();
    }

    @Test
    void theLandingPageAfterSignInIsAPathOnThisSiteAndNeverAnotherAddress() {
        TestUser user = TestUsers.create(users);

        for (String continuePath : List.of("//evil.example.test", "https://evil.example.test",
                "/\\evil.example.test")) {
            TestBrowser browser = signedInWithPassword(TestSignIn.PLATFORM_HOST, user);
            Response start = browser.get("/api/v1/auth/start?continue=" + enc(continuePath));
            Response authorize = browser.get(TestBrowser.location(start));
            Response callback = browser.get(TestBrowser.location(authorize));

            assertThat(callback.header("Location")).as(continuePath).hasValue("/");
        }
        TestBrowser browser = signedInWithPassword(TestSignIn.PLATFORM_HOST, user);
        Response start = browser.get("/api/v1/auth/start?continue=" + enc("/reports?tab=open"));
        Response callback = browser.get(TestBrowser.location(browser.get(TestBrowser.location(start))));
        assertThat(callback.header("Location")).hasValue("/reports?tab=open");
    }

    @Test
    void completingTheSignInSetsTheSessionCookiesWithTheirLifetimesAndRemovesTheTemporaryOnes() {
        TestUser user = TestUsers.create(users);
        TestBrowser browser = signedInWithPassword(TestSignIn.PLATFORM_HOST, user);
        Response start = browser.get("/api/v1/auth/start?continue=/");
        assertThat(start.headers().get("set-cookie")).anyMatch(c -> c.startsWith("platform_tx=")
                && c.contains("Path=/api/v1/auth/callback") && c.contains("HttpOnly") && c.contains("SameSite=Strict"));
        Response authorize = browser.get(TestBrowser.location(start));

        Response callback = browser.get(TestBrowser.location(authorize));

        List<String> cookies = callback.headers().get("set-cookie");
        assertThat(callback.status()).isEqualTo(302);
        String access = cookies.stream().filter(c -> c.startsWith("platform_at=")).findFirst().orElseThrow();
        String refresh = cookies.stream().filter(c -> c.startsWith("platform_rt=")).findFirst().orElseThrow();
        assertThat(maxAge(access)).isBetween(590L, 600L);
        assertThat(maxAge(refresh)).isBetween(8 * 3600 - 10L, 8 * 3600L);
        assertThat(cookies).anyMatch(c -> c.startsWith("platform_tx=") && c.contains("Max-Age=0"));
        assertThat(cookies).anyMatch(c -> c.startsWith("platform_login=") && c.contains("Max-Age=0"));
        assertThat(callback.header("Cache-Control")).hasValue("no-store");
    }

    private static long maxAge(String cookie) {
        for (String part : cookie.split(";")) {
            if (part.strip().startsWith("Max-Age=")) {
                return Long.parseLong(part.strip().substring(8));
            }
        }
        throw new AssertionError("no Max-Age in " + cookie);
    }

    @Test
    void theLoginSessionIsEndedOnTheServerOnceTheSignInIsComplete() {
        TestUser user = TestUsers.create(users);
        TestBrowser browser = signedInWithPassword(TestSignIn.PLATFORM_HOST, user);
        String login = browser.cookie("platform_login").orElseThrow();
        browser.completeSignIn();

        TestBrowser replay = new TestBrowser(port, TestSignIn.PLATFORM_HOST);
        replay.put("platform_login", login);
        Response start = replay.get("/api/v1/auth/start?continue=/");
        Response authorize = replay.get(TestBrowser.location(start));

        assertThat(authorize.header("Location")).hasValue("/sign-in");
    }
}
