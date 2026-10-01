package app.platform.identity;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.sharedkernel.ActorId;
import app.platform.testsupport.IdentityDb;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestSignIn;
import app.platform.testsupport.TestUsers;
import app.platform.testsupport.TestUsers.TestUser;
import app.platform.testsupport.tenancy.TenantFixtures;
import com.jayway.jsonpath.JsonPath;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * What happens to a session after it exists (stories S3-SEC-12 to S3-SEC-17): a protected endpoint refuses callers
 * without a valid token, a signed-out, revoked, suspended or replaced token stops working at once, refresh replaces
 * tokens and a replayed old one ends the sign-in, tokens are stored only as hashes, and a token works only on the host
 * it was issued on.
 */
@PlatformIntegrationTest
class TokenLifecycleIT {

    private static final ActorId ACTOR = new ActorId(UUID.randomUUID());
    private static final String ME = "/api/v1/auth/me";

    @LocalServerPort
    private int port;

    @Autowired
    private Users users;

    private TestBrowser browser(String host) {
        return new TestBrowser(port, host);
    }

    private TestBrowser platformBrowser() {
        return browser(TestSignIn.PLATFORM_HOST);
    }

    private Response meWithBearer(String host, String bearer) {
        return new TestHttp(port).get(ME, "Host", host, "Authorization", bearer);
    }

    private static String setCookie(Response response, String name) {
        return response.headers().getOrDefault("set-cookie", List.of()).stream()
                .filter(cookie -> cookie.startsWith(name + "=")).findFirst().orElse("");
    }

    // ---- who may reach a protected endpoint ----

    @Test
    void aProtectedEndpointRefusesEveryKindOfMissingOrBadCredentialInTheSameWay() {
        List<Response> refusals = new ArrayList<>();
        TestHttp http = new TestHttp(port, "Host", TestSignIn.PLATFORM_HOST);
        refusals.add(http.get(ME));
        refusals.add(http.get(ME, "Authorization", "Bearer not-a-real-token"));
        refusals.add(http.get(ME, "Authorization", "Bearer " + "A".repeat(200)));
        refusals.add(http.get(ME, "Authorization", "Bearer "));
        refusals.add(http.get(ME, "Authorization", "Bearer " + "A".repeat(5_000)));
        refusals.add(http.get(ME, "Cookie", "platform_at=forged-cookie-value"));
        refusals.add(http.get(ME, "Authorization", "Basic dXNlcjpwYXNz"));

        for (Response refusal : refusals) {
            assertThat(refusal.status()).isEqualTo(401);
            assertThat(JsonPath.<String>read(refusal.body(), "$.error.code")).isEqualTo("UNAUTHENTICATED");
            assertThat(JsonPath.<String>read(refusal.body(), "$.error.message"))
                    .isEqualTo("Authentication is required.");
            assertThat(refusal.header("WWW-Authenticate")).hasValue("Bearer");
            assertThat(JsonPath.<String>read(refusal.body(), "$.error.requestId")).isNotBlank();
        }
    }

    @Test
    void aValidTokenIsAcceptedFromTheCookieAndFromTheAuthorizationHeader() {
        TestUser user = TestUsers.create(users);
        TestBrowser browser = platformBrowser();
        TestBrowser.Session session = browser.signIn(user.email(), user.password());

        assertThat(browser.get(ME).status()).isEqualTo(200);
        assertThat(meWithBearer(TestSignIn.PLATFORM_HOST, session.bearer()).status()).isEqualTo(200);
    }

    @Test
    void anExpiredAccessTokenStopsWorking() throws SQLException {
        TestUser user = TestUsers.create(users);
        TestBrowser browser = platformBrowser();
        browser.signIn(user.email(), user.password());

        IdentityDb.execute("update oauth2_authorization set access_token_expires_at = now() - interval '1 minute', "
                + "version = version + 1, updated_by = user_id where user_id = ?", user.user().id());

        assertThat(browser.get(ME).status()).isEqualTo(401);
    }

    // ---- sign-out (S3-SEC-15) ----

    @Test
    void signingOutStopsTheTokenAtOnceAndRemovesEveryCookie() {
        TestUser user = TestUsers.create(users);
        TestBrowser browser = platformBrowser();
        TestBrowser.Session session = browser.signIn(user.email(), user.password());
        assertThat(meWithBearer(TestSignIn.PLATFORM_HOST, session.bearer()).status()).isEqualTo(200);

        Response signOut = browser.post("/api/v1/auth/sign-out");

        assertThat(signOut.status()).isEqualTo(204);
        for (String cookie : List.of("platform_login", "platform_tx", "platform_at", "platform_rt")) {
            assertThat(setCookie(signOut, cookie)).as(cookie).contains("Max-Age=0");
        }
        assertThat(meWithBearer(TestSignIn.PLATFORM_HOST, session.bearer()).status())
                .as("the token that was signed out is dead on the very next request").isEqualTo(401);
        TestBrowser replay = platformBrowser();
        replay.put("platform_rt", session.refreshToken());
        assertThat(replay.post("/api/v1/auth/refresh").status()).as("and so is its refresh token").isEqualTo(401);
        assertThat(IdentityDb.auditOf(user.user().id())).anyMatch(r -> r.type().equals("auth.sign_out"));
    }

    @Test
    void signingOutWhenNotSignedInIsNotAnError() {
        Response response = platformBrowser().post("/api/v1/auth/sign-out");

        assertThat(response.status()).isEqualTo(204);
    }

    @Test
    void signingOutEverywhereEndsEverySessionOfTheUserOnEveryDevice() {
        TestUser user = TestUsers.create(users);
        TestBrowser laptop = platformBrowser();
        TestBrowser phone = browser(TenantFixtures.createActiveTenant().host());
        TestBrowser.Session first = laptop.signIn(user.email(), user.password());
        phone.signIn(user.email(), user.password());
        TestUser other = TestUsers.create(users);
        TestBrowser stranger = platformBrowser();
        stranger.signIn(other.email(), other.password());

        Response everywhere = laptop.post("/api/v1/auth/sign-out-all");

        assertThat(everywhere.status()).isEqualTo(204);
        assertThat(meWithBearer(TestSignIn.PLATFORM_HOST, first.bearer()).status()).isEqualTo(401);
        assertThat(phone.get(ME).status()).isEqualTo(401);
        assertThat(stranger.get(ME).status()).as("another user is untouched").isEqualTo(200);
        assertThat(IdentityDb.auditOf(user.user().id())).anyMatch(r -> r.type().equals("auth.sign_out_all"));
    }

    // ---- revocation by account changes (S3-SEC-14) ----

    @Test
    void suspendingTheUserEndsTheirTokensAndReinstatingDoesNotBringThemBack() {
        TestUser user = TestUsers.create(users);
        TestBrowser browser = platformBrowser();
        TestBrowser.Session session = browser.signIn(user.email(), user.password());

        users.suspend(user.user().id(), ACTOR);
        assertThat(meWithBearer(TestSignIn.PLATFORM_HOST, session.bearer()).status()).isEqualTo(401);

        users.reinstate(user.user().id(), ACTOR);
        assertThat(meWithBearer(TestSignIn.PLATFORM_HOST, session.bearer()).status())
                .as("old tokens stay dead after reinstating").isEqualTo(401);
        assertThat(platformBrowser().signInPassword(user.email(), user.password()).status()).isEqualTo(204);
    }

    @Test
    void deactivatingTheUserEndsTheirTokens() {
        TestUser user = TestUsers.create(users);
        TestBrowser browser = platformBrowser();
        TestBrowser.Session session = browser.signIn(user.email(), user.password());

        users.deactivate(user.user().id(), ACTOR);

        assertThat(meWithBearer(TestSignIn.PLATFORM_HOST, session.bearer()).status()).isEqualTo(401);
    }

    @Test
    void changingThePasswordThroughTheEndpointEndsTheSessionIncludingTheOneThatChangedIt() {
        TestUser user = TestUsers.create(users);
        TestBrowser browser = platformBrowser();
        TestBrowser.Session session = browser.signIn(user.email(), user.password());
        String next = "a brand new violet lantern 55";

        Response changed = browser.postJson("/api/v1/auth/password",
                "{\"currentPassword\":\"" + user.password() + "\",\"newPassword\":\"" + next + "\"}");

        assertThat(changed.status()).isEqualTo(204);
        assertThat(setCookie(changed, "platform_at")).contains("Max-Age=0");
        assertThat(meWithBearer(TestSignIn.PLATFORM_HOST, session.bearer()).status()).isEqualTo(401);
        assertThat(platformBrowser().signInPassword(user.email(), next).status()).isEqualTo(204);
    }

    @Test
    void aWrongCurrentPasswordThroughTheEndpointIsAValidationErrorThatEchoesNothing() {
        TestUser user = TestUsers.create(users);
        TestBrowser browser = platformBrowser();
        browser.signIn(user.email(), user.password());

        Response response = browser.postJson("/api/v1/auth/password",
                "{\"currentPassword\":\"the wrong current password\","
                        + "\"newPassword\":\"a brand new violet lantern 55\"}");

        assertThat(response.status()).isEqualTo(400);
        assertThat(JsonPath.<List<String>>read(response.body(), "$.error.fields.currentPassword")).isNotEmpty();
        assertThat(response.body()).doesNotContain("the wrong current password")
                .doesNotContain("a brand new violet lantern 55");
        assertThat(browser.get(ME).status()).as("a refused change leaves the session alone").isEqualTo(200);
    }

    @Test
    void raisingTheSecurityVersionByHandEndsTheTokensAtOnceWithoutAnyCacheToWaitFor() throws SQLException {
        TestUser user = TestUsers.create(users);
        TestBrowser browser = platformBrowser();
        TestBrowser.Session session = browser.signIn(user.email(), user.password());
        assertThat(browser.get(ME).status()).isEqualTo(200);

        IdentityDb.execute("update platform_user set security_version = security_version + 1, version = version + 1, "
                + "updated_by = ? where id = ?", ACTOR.value(), user.user().id());

        assertThat(meWithBearer(TestSignIn.PLATFORM_HOST, session.bearer()).status()).isEqualTo(401);
    }

    @Test
    void aBrowserHoldingARevokedAccessCookieCanStillSignInRefreshAndSignOut() {
        TestUser user = TestUsers.create(users);
        TestBrowser laptop = platformBrowser();
        laptop.signIn(user.email(), user.password());
        TestBrowser phone = platformBrowser();
        phone.signIn(user.email(), user.password());
        phone.post("/api/v1/auth/sign-out-all");
        assertThat(laptop.get(ME).status()).as("the laptop's cookie is now dead").isEqualTo(401);

        assertThat(laptop.post("/api/v1/auth/sign-out").status()).as("sign-out is not blocked").isEqualTo(204);
        TestBrowser stale = platformBrowser();
        stale.put("platform_at", "a-revoked-or-expired-access-token-value");
        assertThat(stale.signInPassword(user.email(), user.password()).status())
                .as("signing in again is not blocked by the dead cookie").isEqualTo(204);
        assertThat(stale.completeSignIn().accessToken()).isNotBlank();
        assertThat(stale.get(ME).status()).isEqualTo(200);
    }

    @Test
    void aDeadTokenInAnAuthorizationHeaderIsStillRefusedEvenOnAPublicPath() {
        Response response = new TestHttp(port).get("/api/v1/platform/status", "Host", TestSignIn.PLATFORM_HOST,
                "Authorization", "Bearer a-dead-token");

        assertThat(response.status()).as("explicit credentials that are wrong are an error").isEqualTo(401);
    }

    // ---- refresh (S3-SEC-12, S3-SEC-13) ----

    @Test
    void refreshingReplacesBothTokensAndTheOldOnesStopWorking() {
        TestUser user = TestUsers.create(users);
        TestBrowser browser = platformBrowser();
        TestBrowser.Session before = browser.signIn(user.email(), user.password());

        Response refreshed = browser.post("/api/v1/auth/refresh");

        assertThat(refreshed.status()).isEqualTo(204);
        String access = setCookie(refreshed, "platform_at");
        String refresh = setCookie(refreshed, "platform_rt");
        assertThat(access).contains("HttpOnly").contains("SameSite=Strict").contains("Path=/api/v1;");
        assertThat(refresh).contains("HttpOnly").contains("SameSite=Strict").contains("Path=/api/v1/auth;");
        assertThat(browser.cookie("platform_at")).isPresent().isNotEqualTo(java.util.Optional.of(before.accessToken()));
        assertThat(browser.cookie("platform_rt")).isPresent()
                .isNotEqualTo(java.util.Optional.of(before.refreshToken()));
        assertThat(browser.get(ME).status()).isEqualTo(200);
        assertThat(meWithBearer(TestSignIn.PLATFORM_HOST, before.bearer()).status())
                .as("the replaced access token is dead").isEqualTo(401);
        assertThat(IdentityDb.auditOf(user.user().id())).anyMatch(r -> r.type().equals("auth.token.refreshed"));
    }

    @Test
    void aReplayedOldRefreshTokenEndsTheWholeSignInAndIsAudited() throws SQLException {
        TestUser user = TestUsers.create(users);
        TestBrowser victim = platformBrowser();
        TestBrowser.Session stolen = victim.signIn(user.email(), user.password());
        victim.post("/api/v1/auth/refresh");
        assertThat(victim.get(ME).status()).isEqualTo(200);
        // The first replacement happened a while ago, not a moment ago.
        IdentityDb.execute("update oauth2_authorization set refresh_rotated_at = now() - interval '5 minutes', "
                + "version = version + 1, updated_by = user_id where user_id = ?", user.user().id());

        TestBrowser thief = platformBrowser();
        thief.put("platform_rt", stolen.refreshToken());
        Response replay = thief.post("/api/v1/auth/refresh");

        assertThat(replay.status()).isEqualTo(401);
        assertThat(setCookie(replay, "platform_rt")).contains("Max-Age=0");
        assertThat(victim.get(ME).status()).as("the family is revoked, the real user's token too").isEqualTo(401);
        assertThat(victim.post("/api/v1/auth/refresh").status()).isEqualTo(401);
        assertThat(IdentityDb.auditOf(user.user().id())).anyMatch(r -> r.type().equals("auth.refresh.reuse_detected")
                && "rotated_token_replayed".equals(r.reason()));
    }

    @Test
    void aTokenReplacedOnlyAMomentAgoIsRefusedWithoutEndingTheSignInOrClearingCookies() {
        TestUser user = TestUsers.create(users);
        TestBrowser firstTab = platformBrowser();
        TestBrowser.Session session = firstTab.signIn(user.email(), user.password());
        firstTab.post("/api/v1/auth/refresh");

        TestBrowser secondTab = platformBrowser();
        secondTab.put("platform_rt", session.refreshToken());
        Response late = secondTab.post("/api/v1/auth/refresh");

        assertThat(late.status()).isEqualTo(401);
        assertThat(late.headers().getOrDefault("set-cookie", List.of()))
                .as("the cookies the first request set must survive").noneMatch(cookie -> cookie.contains("Max-Age=0"));
        assertThat(firstTab.get(ME).status()).as("the sign-in is intact").isEqualTo(200);
        assertThat(firstTab.post("/api/v1/auth/refresh").status()).isEqualTo(204);
    }

    @Test
    void simultaneousRefreshesOfOneTokenHaveExactlyOneWinnerAndLeaveASessionThatWorks() throws Exception {
        TestUser user = TestUsers.create(users);
        TestBrowser original = platformBrowser();
        TestBrowser.Session session = original.signIn(user.email(), user.password());
        int attempts = 6;
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        try {
            List<TestBrowser> tabs = new ArrayList<>();
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < attempts; i++) {
                TestBrowser tab = platformBrowser();
                tab.put("platform_rt", session.refreshToken());
                tab.put("XSRF-TOKEN", "t");
                tabs.add(tab);
                results.add(pool.submit(() -> {
                    go.await();
                    return tab.post("/api/v1/auth/refresh").status();
                }));
            }
            go.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : results) {
                statuses.add(result.get());
            }

            assertThat(statuses.stream().filter(status -> status == 204).count()).isEqualTo(1);
            assertThat(statuses.stream().filter(status -> status == 401).count()).isEqualTo(attempts - 1);
            TestBrowser winner = tabs.get(statuses.indexOf(204));
            assertThat(winner.get(ME).status()).isEqualTo(200);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aSignInEndsAtItsAbsoluteLifetimeHoweverOftenItWasRefreshed() throws SQLException {
        TestUser user = TestUsers.create(users);
        TestBrowser browser = platformBrowser();
        browser.signIn(user.email(), user.password());
        assertThat(browser.post("/api/v1/auth/refresh").status()).isEqualTo(204);

        IdentityDb.executeWithoutTriggers("update oauth2_authorization set created_at = now() - interval '31 days' "
                + "where user_id = ?", user.user().id());

        assertThat(browser.get(ME).status()).isEqualTo(401);
        assertThat(browser.post("/api/v1/auth/refresh").status()).isEqualTo(401);
    }

    // ---- the host a token belongs to ----

    @Test
    void aTokenWorksOnlyOnTheHostItWasIssuedOn() {
        TestUser user = TestUsers.create(users);
        String hostA = TenantFixtures.createActiveTenant().host();
        String hostB = TenantFixtures.createActiveTenant().host();
        TestBrowser.Session onA = browser(hostA).signIn(user.email(), user.password());

        assertThat(meWithBearer(hostA, onA.bearer()).status()).isEqualTo(200);
        assertThat(meWithBearer(hostB, onA.bearer()).status()).as("another organization").isEqualTo(401);
        assertThat(meWithBearer(TestSignIn.PLATFORM_HOST, onA.bearer()).status()).as("the platform host")
                .isEqualTo(401);
        assertThat(IdentityDb.auditOf(user.user().id())).anyMatch(r -> "wrong_host".equals(r.reason()));
    }

    @Test
    void aPlatformHostTokenIsRefusedOnAnOrganizationHost() {
        TestUser user = TestUsers.create(users);
        String hostA = TenantFixtures.createActiveTenant().host();
        TestBrowser.Session onPlatform = platformBrowser().signIn(user.email(), user.password());

        assertThat(meWithBearer(hostA, onPlatform.bearer()).status()).isEqualTo(401);
    }

    @Test
    void aStolenLoginCookieCannotBeUsedOnAnotherHost() {
        TestUser user = TestUsers.create(users);
        String hostA = TenantFixtures.createActiveTenant().host();
        String hostB = TenantFixtures.createActiveTenant().host();
        TestBrowser onA = browser(hostA);
        onA.signInPassword(user.email(), user.password());
        TestBrowser thief = browser(hostB);
        thief.put("platform_login", onA.cookie("platform_login").orElseThrow());

        Response start = thief.get("/api/v1/auth/start?continue=/");
        Response authorize = thief.get(TestBrowser.location(start));

        assertThat(authorize.status()).isEqualTo(302);
        assertThat(authorize.header("Location")).hasValue("/sign-in");
    }

    // ---- storage: only hashes (S3-SEC-17) ----

    @Test
    void noTokenAndNoLoginCookieIsStoredInTheClearAndTheGrantIsIndexedByUser() throws SQLException {
        TestUser user = TestUsers.create(users);
        TestBrowser browser = platformBrowser();
        assertThat(browser.signInPassword(user.email(), user.password()).status()).isEqualTo(204);
        String login = browser.cookie("platform_login").orElseThrow();
        TestBrowser.Session session = browser.completeSignIn();

        List<String> stored = IdentityDb.strings("select concat_ws('|', authorization_code_value, access_token_value, "
                + "oidc_id_token_value, refresh_token_value) from oauth2_authorization where user_id = ?",
                user.user().id());

        assertThat(stored).hasSize(1);
        for (String value : stored.get(0).split("\\|")) {
            assertThat(value).startsWith("sha256:");
        }
        assertThat(stored.get(0)).doesNotContain(session.accessToken()).doesNotContain(session.refreshToken());
        assertThat(IdentityDb.strings("select token_hash from login_session where user_id = ?", user.user().id()))
                .allSatisfy(hash -> assertThat(hash).startsWith("sha256:").doesNotContain(login));
        assertThat(IdentityDb.strings("select indexname from pg_indexes where tablename = 'oauth2_authorization'"))
                .contains("oauth2_authorization_user");
    }

    @Test
    void tokensAreLongRandomOpaqueValuesThatDifferBetweenSignIns() {
        TestUser user = TestUsers.create(users);

        TestBrowser.Session first = platformBrowser().signIn(user.email(), user.password());
        TestBrowser.Session second = platformBrowser().signIn(user.email(), user.password());

        assertThat(first.accessToken()).hasSizeGreaterThanOrEqualTo(64).matches("[A-Za-z0-9_-]+");
        assertThat(first.accessToken()).isNotEqualTo(second.accessToken());
        assertThat(first.refreshToken()).isNotEqualTo(second.refreshToken());
        assertThat(first.accessToken()).doesNotContain(".").as("opaque, not a readable signed token");
    }

    @Test
    void signingInTwiceGivesTwoIndependentSessions() {
        TestUser user = TestUsers.create(users);
        TestBrowser one = platformBrowser();
        TestBrowser two = platformBrowser();
        one.signIn(user.email(), user.password());
        two.signIn(user.email(), user.password());

        one.post("/api/v1/auth/sign-out");

        assertThat(one.get(ME).status()).isEqualTo(401);
        assertThat(two.get(ME).status()).isEqualTo(200);
    }

    @Test
    void everyTokenIssueAndRefreshIsAudited() {
        TestUser user = TestUsers.create(users);
        TestBrowser browser = platformBrowser();
        browser.signIn(user.email(), user.password());
        browser.post("/api/v1/auth/refresh");

        List<String> types = IdentityDb.auditOf(user.user().id()).stream().map(IdentityDb.Audit::type).toList();

        assertThat(types).contains("auth.sign_in.succeeded", "auth.token.issued", "auth.token.refreshed");
    }
}
