package app.platform.identity;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.sharedkernel.ActorId;
import app.platform.testsupport.IdentityDb;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestSignIn;
import app.platform.testsupport.TestUsers;
import app.platform.testsupport.TestUsers.TestUser;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

/**
 * Story S3-SEC-10 and the decision of Sprint 3 about attacks from many sources: the lock is a delay and never a ban
 * (short, capped, ends by itself, not extended by attempts made during it), the limits stop one source spraying many
 * accounts, and the limit on an identifier ends with its short window, so neither mechanism can keep a person out for
 * long.
 *
 * <p>Honest limit, recorded in ADR-0021: an attacker who keeps failing once per lock period keeps the account locked
 * for as long as they keep trying. The owner is told by e-mail and can reset the password (both arrive with Sprint 4),
 * and the per-source limit makes it costly. A source-aware lock was considered and not chosen for this sprint.
 */
@PlatformIntegrationTest
@TestPropertySource(properties = {
    "platform.identity.rate-limit.source-failures=3",
    "platform.identity.rate-limit.identifier-window=3s"})
class DistributedAttackIT {

    private static final ActorId ACTOR = new ActorId(UUID.randomUUID());
    private static final int CAP_SECONDS = 15 * 60;

    @LocalServerPort
    private int port;

    @Autowired
    private Users users;

    private TestBrowser fromNewSource() {
        return new TestBrowser(port, TestSignIn.PLATFORM_HOST);
    }

    private long audited(UUID user, String type) {
        return IdentityDb.auditOf(user).stream().filter(record -> record.type().equals(type)).count();
    }

    @Test
    void manySourcesAgainstOneAccountLockItButOnlyForAShortCappedTimeAndNeverMoreThanTheirCount()
            throws SQLException {
        TestUser victim = TestUsers.create(users);

        // Forty sources, one attempt each. They share no source limit, so only the account protections act.
        List<Integer> statuses = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            statuses.add(fromNewSource().signInPassword(victim.email(), "guess " + i + " of a botnet").status());
        }

        assertThat(statuses).as("never a success, never a server error").allMatch(status -> status == 401
                || status == 429);
        assertThat(statuses.stream().filter(status -> status == 429).count())
                .as("the identifier limit shields the account's hashing from the flood").isPositive();
        Long attempts = IdentityDb.value(Long.class, "select failed_attempts::bigint from user_credential "
                + "where user_id = ?", victim.user().id());
        assertThat(attempts).as("attempts during the lock and refused attempts are not counted").isBetween(5L, 10L);
        Long seconds = IdentityDb.value(Long.class, "select extract(epoch from (locked_until - now()))::bigint "
                + "from user_credential where user_id = ?", victim.user().id());
        assertThat(seconds).as("the lock is short").isBetween(1L, 60L);
    }

    @Test
    void theLockGrowsToTheCapAndNeverBeyondHoweverLongTheAttackGoesOn() throws SQLException {
        TestUser victim = TestUsers.create(users);
        for (int i = 0; i < 5; i++) {
            fromNewSource().signInPassword(victim.email(), "guess " + i + " of a botnet");
        }

        // Time passes (the end of each lock is moved into the past) and the attacker returns with one more guess.
        for (int round = 0; round < 6; round++) {
            IdentityDb.execute("update user_credential set locked_until = now() - interval '1 second', "
                    + "version = version + 1, updated_by = ? where user_id = ?", ACTOR.value(), victim.user().id());
            // The identifier window is short; wait it out so the attempt is evaluated.
            sleep(3_100);
            fromNewSource().signInPassword(victim.email(), "one more guess " + round);
        }

        List<Long> lockSeconds = IdentityDb.auditOf(victim.user().id()).stream()
                .filter(record -> record.type().equals("auth.account.locked"))
                .map(record -> Long.parseLong(record.attributes().replaceAll(".*\"lock_seconds\": \"(\\d+)\".*", "$1")))
                .toList();
        assertThat(lockSeconds).startsWith(60L, 120L, 240L, 480L, 900L);
        assertThat(lockSeconds).allMatch(length -> length <= CAP_SECONDS);
        assertThat(lockSeconds.get(lockSeconds.size() - 1)).isEqualTo(900L);
    }

    @Test
    void theOwnerGetsInAfterTheLockEndsAndAfterTheShortIdentifierWindow() throws SQLException {
        TestUser owner = TestUsers.create(users);
        for (int i = 0; i < 12; i++) {
            fromNewSource().signInPassword(owner.email(), "a flood of guesses " + i);
        }
        assertThat(fromNewSource().signInPassword(owner.email(), owner.password()).status())
                .as("during the attack even the owner is refused").isIn(401, 429);

        // The lock ends by itself and the identifier window (three seconds here) passes.
        IdentityDb.execute("update user_credential set locked_until = now() - interval '1 second', "
                + "version = version + 1, updated_by = ? where user_id = ?", ACTOR.value(), owner.user().id());
        sleep(3_200);

        assertThat(fromNewSource().signInPassword(owner.email(), owner.password()).status())
                .as("the attack never became a ban").isEqualTo(204);
    }

    @Test
    void oneSourceSprayingOnePasswordOverManyAccountsIsStoppedAfterAFewTries() {
        List<TestUser> victims = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            victims.add(TestUsers.create(users));
        }
        TestBrowser sprayer = fromNewSource();
        List<Integer> statuses = new ArrayList<>();

        for (TestUser victim : victims) {
            statuses.add(sprayer.signInPassword(victim.email(), "Summer2026-company").status());
        }

        assertThat(statuses.stream().filter(status -> status == 401).count()).isEqualTo(3);
        assertThat(statuses.stream().filter(status -> status == 429).count()).isEqualTo(9);
        for (TestUser victim : victims.subList(3, victims.size())) {
            assertThat(audited(victim.user().id(), "auth.sign_in.failed"))
                    .as("the refused ones were not even looked at").isZero();
        }
    }

    @Test
    void aRefusedAttemptIsTheSameForAnExistingAndAnUnknownAccount() {
        TestBrowser sprayer = fromNewSource();
        for (int i = 0; i < 3; i++) {
            sprayer.signInPassword("nobody-" + i + "@example.test", "x".repeat(15));
        }

        Response known = sprayer.signInPassword(TestUsers.create(users).email(), "another guess here");
        Response unknown = sprayer.signInPassword("never-existed-" + UUID.randomUUID() + "@example.test",
                "another guess here");

        assertThat(known.status()).isEqualTo(429);
        assertThat(unknown.status()).isEqualTo(429);
        assertThat(known.body().replaceAll("\"(requestId|traceId)\":\"[^\"]*\"", ""))
                .isEqualTo(unknown.body().replaceAll("\"(requestId|traceId)\":\"[^\"]*\"", ""));
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
