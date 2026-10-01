package app.platform.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;

import app.platform.identity.AuthenticationAttempt.PasswordAttempt;
import app.platform.identity.AuthenticationOutcome;
import app.platform.identity.RejectionReason;
import app.platform.identity.Users;
import app.platform.sharedkernel.ActorId;
import app.platform.testsupport.IdentityDb;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestUsers;
import app.platform.testsupport.TestUsers.TestUser;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Story S3-SEC-06: the same work on every path. Exactly one password verification runs whatever the state of the
 * account (counted), and the time a decision takes does not depend on the state (a regression guard against an early
 * return, with a realistic hash cost so that hashing dominates the measurement).
 */
@PlatformIntegrationTest
@TestPropertySource(properties = {
    "platform.identity.password.memory-kib=19456",
    "platform.identity.password.iterations=2"})
class ConstantWorkIT {

    private static final ActorId ACTOR = new ActorId(UUID.randomUUID());
    private static final int SAMPLES = 15;

    @Autowired
    private Users users;

    @Autowired
    private LocalCredentialsProvider provider;

    @MockitoSpyBean
    private PasswordHasher hasher;

    private AuthenticationOutcome attempt(String email, String password) {
        return provider.authenticate(new PasswordAttempt(email, password.toCharArray(), "203.0.113.5"));
    }

    private TestUser lockedUser() throws SQLException {
        TestUser user = TestUsers.create(users);
        IdentityDb.execute("update user_credential set locked_until = now() + interval '10 minutes', "
                + "failed_attempts = 5, version = version + 1, updated_by = ? where user_id = ?", ACTOR.value(),
                user.user().id());
        return user;
    }

    private TestUser disabledUser() {
        TestUser user = TestUsers.create(users);
        users.suspend(user.user().id(), ACTOR);
        return user;
    }

    private static String stranger() {
        return "nobody-" + UUID.randomUUID() + "@example.test";
    }

    private int verifications() {
        // Each of these is exactly one password verification; every attempt must trigger exactly one in total.
        int real = org.mockito.Mockito.mockingDetails(hasher).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("matches")).toList().size();
        int dummy = org.mockito.Mockito.mockingDetails(hasher).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("matchesNothing")).toList().size();
        return real + dummy;
    }

    @Test
    void exactlyOneVerificationRunsOnEveryPathWhateverTheStateOfTheAccount() throws SQLException {
        TestUser wrong = TestUsers.create(users);
        TestUser right = TestUsers.create(users);
        TestUser locked = lockedUser();
        TestUser disabled = disabledUser();
        String invitedEmail = "invited-" + UUID.randomUUID() + "@example.test";
        users.createInvited(invitedEmail, "Invited", ACTOR);

        record Case(String name, String email, String password, RejectionReason expected) {
        }
        List<Case> cases = List.of(
                new Case("unknown", stranger(), "some password typed", RejectionReason.UNKNOWN_ACCOUNT),
                new Case("wrong password", wrong.email(), "not the password at all", RejectionReason.WRONG_PASSWORD),
                new Case("locked, right password", locked.email(), locked.password(), RejectionReason.LOCKED),
                new Case("disabled, right password", disabled.email(), disabled.password(), RejectionReason.DISABLED),
                new Case("invited, never verified", invitedEmail, "some password typed", RejectionReason.NOT_VERIFIED),
                new Case("a password longer than any valid one", wrong.email(), "x".repeat(900),
                        RejectionReason.WRONG_PASSWORD));
        for (Case c : cases) {
            clearInvocations(hasher);

            AuthenticationOutcome outcome = attempt(c.email(), c.password());

            assertThat(outcome).as(c.name()).isInstanceOfSatisfying(AuthenticationOutcome.Rejected.class,
                    rejected -> assertThat(rejected.reason()).isEqualTo(c.expected()));
            assertThat(verifications()).as(c.name() + ": number of password verifications").isEqualTo(1);
        }

        clearInvocations(hasher);
        assertThat(attempt(right.email(), right.password())).isInstanceOf(AuthenticationOutcome.Authenticated.class);
        // The right password: one verification, and no second hash because the stored hash is current.
        assertThat(verifications()).isEqualTo(1);
    }

    @Test
    void theTimeADecisionTakesDoesNotDependOnTheStateOfTheAccount() throws SQLException {
        List<Supplier<Runnable>> unknown = new ArrayList<>();
        List<Supplier<Runnable>> wrong = new ArrayList<>();
        List<Supplier<Runnable>> locked = new ArrayList<>();
        List<Supplier<Runnable>> disabled = new ArrayList<>();
        for (int i = 0; i < SAMPLES; i++) {
            String stranger = stranger();
            unknown.add(() -> () -> attempt(stranger, "a wrong password for timing"));
            TestUser w = TestUsers.create(users);
            wrong.add(() -> () -> attempt(w.email(), "a wrong password for timing"));
            TestUser l = lockedUser();
            locked.add(() -> () -> attempt(l.email(), l.password()));
            TestUser d = disabledUser();
            disabled.add(() -> () -> attempt(d.email(), d.password()));
        }
        // Warm up the code paths and the connection pool before measuring.
        attempt(stranger(), "warm up");
        attempt(TestUsers.create(users).email(), "warm up");

        double reference = median(wrong);
        double unknownTime = median(unknown);
        double lockedTime = median(locked);
        double disabledTime = median(disabled);

        // A coarse guard: it catches an early return (which would be many times faster), not a side channel.
        assertThat(unknownTime / reference).as("unknown vs wrong password").isBetween(0.5, 2.0);
        assertThat(lockedTime / reference).as("locked vs wrong password").isBetween(0.5, 2.0);
        assertThat(disabledTime / reference).as("disabled vs wrong password").isBetween(0.5, 2.0);
        assertThat(reference).as("hashing dominates, so the guard means something").isGreaterThan(5_000_000.0);
    }

    private static double median(List<Supplier<Runnable>> samples) {
        List<Long> nanos = new ArrayList<>();
        for (Supplier<Runnable> sample : samples) {
            Runnable run = sample.get();
            long start = System.nanoTime();
            run.run();
            nanos.add(System.nanoTime() - start);
        }
        Collections.sort(nanos);
        return nanos.get(nanos.size() / 2);
    }
}
