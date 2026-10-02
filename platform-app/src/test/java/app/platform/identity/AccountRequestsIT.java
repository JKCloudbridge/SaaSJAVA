package app.platform.identity;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.sharedkernel.ActorId;
import app.platform.testsupport.IdentityDb;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestSignIn;
import app.platform.testsupport.TestWarmUp;
import app.platform.testsupport.tenancy.TenantFixtures;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.BiFunction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The request steps of sign-up and password reset (Sprint 4, ADR-0023, story S3-SEC-07): one answer for every kind of
 * address, the same work for all of them, the limits, and the rules of where and how the endpoints may be called. The
 * mail relay is not run here; the e-mail flows are in the notification module's tests.
 */
@PlatformIntegrationTest
class AccountRequestsIT {

    private static final ActorId ACTOR = new ActorId(UUID.randomUUID());
    private static final String PASSWORD = "pw-" + UUID.randomUUID() + "-" + UUID.randomUUID();

    @LocalServerPort
    private int port;

    @Autowired
    private Users users;

    @BeforeEach
    void redisIsWarm() {
        TestWarmUp.redis(port);
    }

    private TestBrowser browser() {
        return new TestBrowser(port, TestSignIn.PLATFORM_HOST);
    }

    private static String address() {
        return "person-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12) + "@example.test";
    }

    private static Response signUp(TestBrowser browser, String email) {
        return browser.postJson("/api/v1/auth/sign-up", "{\"email\":\"" + email + "\"}");
    }

    private static Response forgot(TestBrowser browser, String email) {
        return browser.postJson("/api/v1/auth/password/forgot", "{\"email\":\"" + email + "\"}");
    }

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

    /** One address in each state an address can be in. */
    private Map<String, String> addressesInEveryState() throws SQLException {
        Map<String, String> byState = new TreeMap<>();
        byState.put("unknown", address());
        byState.put("active", activeAddress());
        String suspended = activeAddress();
        users.suspend(users.findByEmail(suspended).orElseThrow().id(), ACTOR);
        byState.put("suspended", suspended);
        String deactivated = activeAddress();
        users.deactivate(users.findByEmail(deactivated).orElseThrow().id(), ACTOR);
        byState.put("deactivated", deactivated);
        String invited = address();
        users.createInvited(invited, "Person C", ACTOR);
        byState.put("invited", invited);
        String deleted = activeAddress();
        IdentityDb.executeWithoutTriggers("update platform_user set deleted_at = now(), deleted_by = ? "
                + "where email = ?", ACTOR.value(), deleted);
        byState.put("deleted", deleted);
        return byState;
    }

    private String activeAddress() {
        String email = address();
        users.createActive(email, "Person A", PASSWORD.toCharArray(), ACTOR);
        return email;
    }

    // ---- one answer for every address (S3-SEC-07) ----

    @Test
    void signUpAnswersTheSameForEveryKindOfAddressAndDoesTheSameWork() throws SQLException {
        assertUniform(AccountRequestsIT::signUp, "SIGN_UP_REQUEST", "auth.sign_up.requested");
    }

    @Test
    void passwordResetAnswersTheSameForEveryKindOfAddressAndDoesTheSameWork() throws SQLException {
        assertUniform(AccountRequestsIT::forgot, "PASSWORD_RESET_REQUEST", "auth.password_reset.requested");
    }

    private void assertUniform(BiFunction<TestBrowser, String, Response> request, String template, String auditType)
            throws SQLException {
        Map<String, String> addresses = addressesInEveryState();
        Map<String, String> answers = new TreeMap<>();
        for (Map.Entry<String, String> entry : addresses.entrySet()) {
            answers.put(entry.getKey(), observable(request.apply(browser(), entry.getValue())));
        }

        assertThat(answers.values().stream().distinct()).as("answers by state: %s", answers).hasSize(1);
        assertThat(answers.get("unknown")).startsWith("202 ");
        for (Map.Entry<String, String> entry : addresses.entrySet()) {
            String email = entry.getValue();
            assertThat(IdentityDb.value(Long.class, "select count(*) from mail_queue where email = ? "
                    + "and template = ? and status = 'QUEUED'", email, template))
                    .as("one queued request for the %s address", entry.getKey()).isEqualTo(1L);
            assertThat(IdentityDb.value(Long.class, "select count(*) from account_token where email = ?", email))
                    .as("no token at request time for the %s address", entry.getKey()).isZero();
        }
        String hash = IdentityDb.value(String.class,
                "select encode(sha256(convert_to(?, 'UTF8')), 'hex')", addresses.get("unknown"));
        assertThat(IdentityDb.value(Long.class, "select count(*) from audit_record where event_type = ? "
                + "and attributes ->> 'identifier_hash' = ?", auditType, hash)).isEqualTo(1L);
        assertThat(IdentityDb.entireAuditTableAsText()).as("the typed address is not in the audit trail")
                .doesNotContain(addresses.get("unknown"));
        assertThat(users.findByEmail(addresses.get("unknown"))).as("nothing is created by a request").isEmpty();
    }

    @Test
    void textThatCannotBeAnAddressIsRefusedTheSameWayEverywhere() {
        for (String text : List.of("not-an-address", "a@@b", "", "   ")) {
            Response signUp = signUp(browser(), text);
            Response forgot = forgot(browser(), text);
            assertThat(signUp.status()).as(text).isEqualTo(400);
            assertThat(forgot.status()).as(text).isEqualTo(400);
        }
    }

    @Test
    void theAnswerIsCoarselyAsFastForAKnownAddressAsForAnUnknownOne() throws SQLException {
        // A guard against an early return or a lookup on the request path, not a side-channel analysis. The unit
        // test of the service proves the lookup is not made; this one watches the whole request.
        List<String> known = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            known.add(activeAddress());
        }
        List<Long> knownTimes = new ArrayList<>();
        List<Long> unknownTimes = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            signUp(browser(), known.get(i));
            signUp(browser(), address());
        }
        for (int i = 4; i < 8; i++) {
            long start = System.nanoTime();
            signUp(browser(), known.get(i));
            knownTimes.add(System.nanoTime() - start);
            start = System.nanoTime();
            signUp(browser(), address());
            unknownTimes.add(System.nanoTime() - start);
        }

        double ratio = median(knownTimes) / median(unknownTimes);

        assertThat(ratio).as("known/unknown median time ratio").isBetween(0.5, 2.0);
    }

    private static double median(List<Long> values) {
        List<Long> sorted = values.stream().sorted().toList();
        return sorted.get(sorted.size() / 2);
    }

    // ---- limits (they count every address alike) ----

    @Test
    void aSourceMayStartFiveSignUpsAnHourAndTheLimitDoesNotDependOnTheAddress() {
        TestBrowser browser = browser();
        String known = activeAddress();

        for (int i = 0; i < 5; i++) {
            assertThat(signUp(browser, i % 2 == 0 ? known : address()).status()).isEqualTo(202);
        }
        Response sixthUnknown = signUp(browser, address());
        Response sixthKnown = signUp(browser, known);

        assertThat(sixthUnknown.status()).isEqualTo(429);
        assertThat(observable(sixthKnown)).isEqualTo(observable(sixthUnknown));
        assertThat(sixthUnknown.header("Retry-After")).isPresent();
    }

    @Test
    void aSourceMayAskForTenPasswordResetsAnHour() {
        TestBrowser browser = browser();

        for (int i = 0; i < 10; i++) {
            assertThat(forgot(browser, address()).status()).isEqualTo(202);
        }

        assertThat(forgot(browser, address()).status()).isEqualTo(429);
    }

    @Test
    void anAddressCanBeMailedFiveTimesAnHourWhoeverAsksAndWhateverTheKind() {
        String unknown = address();
        String known = activeAddress();

        for (String email : List.of(unknown, known)) {
            for (int i = 0; i < 5; i++) {
                Response response = i % 2 == 0 ? signUp(browser(), email) : forgot(browser(), email);
                assertThat(response.status()).as(email + " request " + i).isEqualTo(202);
            }
        }

        assertThat(signUp(browser(), unknown).status()).isEqualTo(429);
        assertThat(forgot(browser(), known).status()).isEqualTo(429);
        assertThat(observable(signUp(browser(), unknown))).isEqualTo(observable(signUp(browser(), known)));
    }

    @Test
    void aSourceMayTryTwentyLinksInTenMinutes() {
        TestBrowser browser = browser();

        for (int i = 0; i < 20; i++) {
            Response guess = browser.postJson("/api/v1/auth/sign-up/complete", "{\"token\":\"guess-" + i
                    + "\",\"displayName\":\"Person A\",\"password\":\"" + PASSWORD + "\"}");
            assertThat(guess.status()).as("guess " + i).isEqualTo(400);
        }
        Response twentyFirst = browser.postJson("/api/v1/auth/password/reset",
                "{\"token\":\"guess-x\",\"newPassword\":\"" + PASSWORD + "\"}");

        assertThat(twentyFirst.status()).isEqualTo(429);
    }

    // ---- where and how the endpoints may be called ----

    @Test
    void theFourEndpointsDoNotExistOnAnOrganizationHost() {
        TestBrowser inside = new TestBrowser(port, TenantFixtures.createActiveTenant().host());

        assertThat(signUp(inside, address()).status()).isEqualTo(404);
        assertThat(forgot(inside, address()).status()).isEqualTo(404);
        assertThat(inside.postJson("/api/v1/auth/sign-up/complete", "{\"token\":\"x\",\"displayName\":\"A\","
                + "\"password\":\"" + PASSWORD + "\"}").status()).isEqualTo(404);
        assertThat(inside.postJson("/api/v1/auth/password/reset", "{\"token\":\"x\",\"newPassword\":\""
                + PASSWORD + "\"}").status()).isEqualTo(404);
    }

    @Test
    void theFourEndpointsAreProtectedAgainstCrossSiteForgery() {
        TestBrowser browser = browser();
        String body = "{\"email\":\"" + address() + "\"}";

        assertThat(browser.postJsonWithoutCsrfHeader("/api/v1/auth/sign-up", body).status()).isEqualTo(403);
        assertThat(browser.postJsonWithoutCsrfHeader("/api/v1/auth/password/forgot", body).status()).isEqualTo(403);
        assertThat(browser.postJsonWithoutCsrfHeader("/api/v1/auth/sign-up/complete",
                "{\"token\":\"x\",\"displayName\":\"A\",\"password\":\"" + PASSWORD + "\"}").status()).isEqualTo(403);
        assertThat(browser.postJsonWithoutCsrfHeader("/api/v1/auth/password/reset",
                "{\"token\":\"x\",\"newPassword\":\"" + PASSWORD + "\"}").status()).isEqualTo(403);
    }

    @Test
    void aStaleAccessCookieDoesNotStopAPublicRequest() {
        TestBrowser browser = browser();
        browser.put("platform_at", "an-access-token-that-was-revoked");

        assertThat(signUp(browser, address()).status()).isEqualTo(202);
        assertThat(forgot(browser, address()).status()).isEqualTo(202);
    }
}
