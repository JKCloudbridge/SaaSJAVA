package app.platform.identity;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.sharedkernel.ActorId;
import app.platform.testsupport.IdentityDb;
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
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Sign-in over real HTTP: where it works, that every kind of failure looks the same (stories S3-SEC-05 and S3-SEC-10),
 * the account lock end to end (S3-SEC-08), rate limiting (S3-SEC-09), forgery protection (S3-SEC-18) and what is never
 * written to a response or a record (S3-SEC-04, S3-SEC-11).
 */
@PlatformIntegrationTest
class SignInIT {

    private static final ActorId ACTOR = new ActorId(UUID.randomUUID());
    private static final String WRONG = "definitely not the password 123";

    @LocalServerPort
    private int port;

    @Autowired
    private Users users;

    private TestBrowser browser() {
        return new TestBrowser(port, TestSignIn.PLATFORM_HOST);
    }

    /** Everything a caller can see of a response except what is unique per request. */
    private static String observable(Response response) {
        Map<String, String> headers = new TreeMap<>();
        response.headers().forEach((name, values) -> {
            String lower = name.toLowerCase();
            if (!lower.equals("date") && !lower.equals("x-request-id") && !lower.equals("x-trace-id")
                    && !lower.equals("set-cookie") && !lower.equals("content-length")) {
                headers.put(lower, String.join("|", values));
            }
        });
        String body = response.body().replaceAll("\"requestId\":\"[^\"]*\"", "\"requestId\":\"-\"")
                .replaceAll("\"traceId\":\"[^\"]*\"", "\"traceId\":\"-\"");
        return response.status() + " " + headers + " " + body;
    }

    // ---- where it works ----

    @Test
    void signInWorksOnTheOrganizationHostAndOnThePlatformHost() {
        TestUser user = TestUsers.create(users);
        String organizationHost = TenantFixtures.createActiveTenant().host();
        TestMembers.addForHost(organizationHost, user.user().id());

        for (String host : List.of(organizationHost, TestSignIn.PLATFORM_HOST)) {
            TestBrowser browser = new TestBrowser(port, host);
            TestBrowser.Session session = browser.signIn(user.email(), user.password());

            Response me = browser.get("/api/v1/auth/me");
            assertThat(me.status()).as(host).isEqualTo(200);
            assertThat(JsonPath.<String>read(me.body(), "$.data.id")).isEqualTo(user.user().id().toString());
            assertThat(session.accessToken()).isNotBlank();
            assertThat(session.refreshToken()).isNotBlank().isNotEqualTo(session.accessToken());
        }
    }

    @Test
    void theAddressIsFoundInAnyCase() {
        TestUser user = TestUsers.create(users);

        assertThat(browser().signInPassword(user.email().toUpperCase(), user.password()).status()).isEqualTo(204);
    }

    // ---- uniform failure (S3-SEC-05) ----

    @Test
    void unknownWrongPasswordLockedDisabledAndUnverifiedAccountsAllGetTheSameAnswer() throws SQLException {
        TestUser wrong = TestUsers.create(users);
        TestUser locked = TestUsers.create(users);
        IdentityDb.execute("update user_credential set locked_until = now() + interval '10 minutes', "
                + "failed_attempts = 5, version = version + 1, updated_by = ? where user_id = ?", ACTOR.value(),
                locked.user().id());
        TestUser disabled = TestUsers.create(users);
        users.suspend(disabled.user().id(), ACTOR);
        User unverified = users.createInvited("unverified-" + UUID.randomUUID() + "@example.test", "Unverified",
                ACTOR);
        TestUser deactivated = TestUsers.create(users);
        users.deactivate(deactivated.user().id(), ACTOR);

        List<String> answers = new ArrayList<>();
        answers.add(observable(browser().signInPassword("nobody-" + UUID.randomUUID() + "@example.test", WRONG)));
        answers.add(observable(browser().signInPassword(wrong.email(), WRONG)));
        // Even the right password is refused while locked, disabled or deactivated.
        answers.add(observable(browser().signInPassword(locked.email(), locked.password())));
        answers.add(observable(browser().signInPassword(disabled.email(), disabled.password())));
        answers.add(observable(browser().signInPassword(deactivated.email(), deactivated.password())));
        answers.add(observable(browser().signInPassword(unverified.email(), WRONG)));

        assertThat(answers).hasSize(6);
        assertThat(answers.stream().distinct().toList())
                .as("status, headers and body must not tell the cases apart").hasSize(1);
        assertThat(answers.get(0)).startsWith("401 ").contains("\"code\":\"UNAUTHENTICATED\"")
                .contains("The email address or the password is not correct.");
    }

    @Test
    void aFailedSignInSetsNoSessionCookieAndRevealsNothingAboutTheAccount() {
        TestUser user = TestUsers.create(users);
        TestBrowser browser = browser();

        Response response = browser.signInPassword(user.email(), WRONG);

        assertThat(response.headers().getOrDefault("set-cookie", List.of()))
                .noneMatch(cookie -> cookie.contains("platform_"));
        assertThat(response.body()).doesNotContain(user.email()).doesNotContain(user.user().id().toString())
                .doesNotContain("locked").doesNotContain("suspended").doesNotContain("exist");
    }

    @Test
    void theTrueReasonOfEachFailureIsAuditedWithoutAnySecret() throws SQLException {
        TestUser wrong = TestUsers.create(users);
        TestUser disabled = TestUsers.create(users);
        users.suspend(disabled.user().id(), ACTOR);
        String stranger = "nobody-" + UUID.randomUUID() + "@example.test";

        browser().signInPassword(wrong.email(), WRONG);
        browser().signInPassword(disabled.email(), disabled.password());
        browser().signInPassword(stranger, WRONG);

        assertThat(IdentityDb.auditOf(wrong.user().id())).anyMatch(
                r -> r.type().equals("auth.sign_in.failed") && "wrong_password".equals(r.reason()));
        assertThat(IdentityDb.auditOf(disabled.user().id())).anyMatch(
                r -> r.type().equals("auth.sign_in.failed") && "disabled".equals(r.reason()));
        assertThat(IdentityDb.auditOfType("auth.sign_in.failed")).anyMatch(
                r -> "unknown_account".equals(r.reason()) && r.userId() == null);
        String everything = IdentityDb.entireAuditTableAsText();
        assertThat(everything).doesNotContain(WRONG).doesNotContain(disabled.password())
                .doesNotContain(wrong.password()).doesNotContain(stranger);
    }

    // ---- the lock (S3-SEC-08) ----

    @Test
    void fiveFailuresLockTheAccountEvenAgainstTheRightPasswordAndTheLockEndsByItself() throws SQLException {
        TestUser user = TestUsers.create(users);
        for (int i = 0; i < 5; i++) {
            assertThat(browser().signInPassword(user.email(), WRONG + i).status()).isEqualTo(401);
        }

        assertThat(IdentityDb.value(Long.class, "select count(*) from user_credential where user_id = ? "
                + "and locked_until > now()", user.user().id())).isEqualTo(1L);
        assertThat(browser().signInPassword(user.email(), user.password()).status())
                .as("the right password is refused while the account is locked").isEqualTo(401);
        assertThat(IdentityDb.auditOf(user.user().id())).anyMatch(r -> r.type().equals("auth.account.locked")
                && r.attributes().contains("\"lock_seconds\": \"60\""));

        // Time passes (the stored end of the lock is moved into the past, as the clock would).
        IdentityDb.execute("update user_credential set locked_until = now() - interval '1 second', "
                + "version = version + 1, updated_by = ? where user_id = ?", ACTOR.value(), user.user().id());

        assertThat(browser().signInPassword(user.email(), user.password()).status()).isEqualTo(204);
    }

    @Test
    void attemptsDuringALockDoNotExtendItAndASuccessClearsTheCount() throws SQLException {
        TestUser user = TestUsers.create(users);
        for (int i = 0; i < 5; i++) {
            browser().signInPassword(user.email(), WRONG + i);
        }
        Long attemptsAtLock = IdentityDb.value(Long.class,
                "select failed_attempts::bigint from user_credential where user_id = ?", user.user().id());
        OffsetDateTime lockedUntil = IdentityDb.value(OffsetDateTime.class,
                "select locked_until from user_credential where user_id = ?", user.user().id());

        // Three more, so that with the first five and the final success the account stays within the identifier
        // limit of ten attempts a minute (that limit is tested on its own).
        for (int i = 0; i < 3; i++) {
            assertThat(browser().signInPassword(user.email(), WRONG + "again" + i).status()).isEqualTo(401);
        }

        assertThat(IdentityDb.value(Long.class, "select failed_attempts::bigint from user_credential "
                + "where user_id = ?", user.user().id())).isEqualTo(attemptsAtLock);
        assertThat(IdentityDb.value(OffsetDateTime.class, "select locked_until from user_credential "
                + "where user_id = ?", user.user().id())).isEqualTo(lockedUntil);

        IdentityDb.execute("update user_credential set locked_until = now() - interval '1 second', "
                + "version = version + 1, updated_by = ? where user_id = ?", ACTOR.value(), user.user().id());
        assertThat(browser().signInPassword(user.email(), user.password()).status()).isEqualTo(204);
        assertThat(IdentityDb.value(Long.class, "select failed_attempts::bigint from user_credential "
                + "where user_id = ?", user.user().id())).isZero();
    }

    // ---- input and limits ----

    @Test
    void aMissingPasswordIsAValidationErrorNotASignInFailure() {
        Response response = browser().postJson("/api/v1/auth/sign-in", "{\"email\":\"a@example.test\"}");

        assertThat(response.status()).isEqualTo(400);
        assertThat(JsonPath.<String>read(response.body(), "$.error.code")).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void anEnormousPasswordIsRefusedBeforeAnyHashing() {
        String huge = "x".repeat(5_000);

        Response response = browser().postJson("/api/v1/auth/sign-in",
                "{\"email\":\"a@example.test\",\"password\":\"" + huge + "\"}");

        assertThat(response.status()).isEqualTo(400);
    }

    @Test
    void aPasswordLongerThanAnyValidOneIsAnOrdinaryFailureAndNeverMatches() {
        TestUser user = TestUsers.create(users);

        Response response = browser().signInPassword(user.email(), "x".repeat(900));

        assertThat(response.status()).isEqualTo(401);
    }

    @Test
    void theSourceIsStoppedAfterTooManyFailuresWithARetryHeaderInTheErrorModel() {
        TestBrowser attacker = browser().fromSource("198.51.100." + (int) (Math.random() * 200 + 20));
        Response last = null;

        for (int i = 0; i < 30; i++) {
            last = attacker.signInPassword("someone-" + i + "@example.test", WRONG);
        }

        assertThat(last.status()).isEqualTo(429);
        assertThat(JsonPath.<String>read(last.body(), "$.error.code")).isEqualTo("RATE_LIMITED");
        assertThat(last.header("Retry-After")).isPresent();
    }

    // ---- forgery protection (S3-SEC-18) ----

    @Test
    void theSignInRefusesARequestWithoutTheForgeryHeader() {
        TestUser user = TestUsers.create(users);

        Response response = browser().postJsonWithoutCsrfHeader("/api/v1/auth/sign-in",
                "{\"email\":\"" + user.email() + "\",\"password\":\"" + user.password() + "\"}");

        assertThat(response.status()).isEqualTo(403);
        assertThat(JsonPath.<String>read(response.body(), "$.error.code")).isEqualTo("FORBIDDEN");
    }

    @Test
    void theSignInRefusesAForgeryHeaderThatDoesNotMatchTheCookie() {
        TestUser user = TestUsers.create(users);
        TestBrowser browser = browser();
        browser.get("/api/v1/auth/csrf");

        Response response = browser.postJson("/api/v1/auth/sign-in",
                "{\"email\":\"" + user.email() + "\",\"password\":\"" + user.password() + "\"}",
                "X-XSRF-TOKEN", "a-value-an-attacker-guessed");

        assertThat(response.status()).isEqualTo(403);
    }

    @Test
    void theForgeryCookieIsReadableByThePageButNeverSentFromAnotherSite() {
        Response response = new TestHttp(port).get("/api/v1/auth/csrf", "Host", TestSignIn.PLATFORM_HOST);

        String cookie = response.headers().get("set-cookie").stream().filter(c -> c.startsWith("XSRF-TOKEN="))
                .findFirst().orElseThrow();
        assertThat(response.status()).isEqualTo(204);
        assertThat(cookie).doesNotContain("HttpOnly").contains("SameSite=Strict");
    }

    @Test
    void theLoginCookieIsHttpOnlyStrictAndLimitedToTheAuthorizationPath() {
        TestUser user = TestUsers.create(users);
        TestBrowser browser = browser();
        browser.get("/api/v1/auth/csrf");

        Response response = browser.signInPassword(user.email(), user.password());

        String cookie = response.headers().get("set-cookie").stream().filter(c -> c.startsWith("platform_login="))
                .findFirst().orElseThrow();
        assertThat(cookie).contains("HttpOnly").contains("SameSite=Strict")
                .contains("Path=/api/v1/oauth2/authorize")
                .contains("Max-Age=" + Duration.ofMinutes(5).toSeconds());
    }

    // ---- nothing secret in what is sent back ----

    @Test
    void noResponseOfTheSignInEverContainsThePasswordOrTheHash() {
        TestUser user = TestUsers.create(users);

        List<Response> responses = List.of(
                browser().signInPassword(user.email(), WRONG),
                browser().signInPassword(user.email(), user.password()),
                browser().postJson("/api/v1/auth/sign-in", "{ not json " + WRONG));

        for (Response response : responses) {
            assertThat(response.body()).doesNotContain(WRONG).doesNotContain(user.password())
                    .doesNotContain("argon2");
            assertThat(response.headers().toString()).doesNotContain(WRONG).doesNotContain(user.password());
        }
    }
}
