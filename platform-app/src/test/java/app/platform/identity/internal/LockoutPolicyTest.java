package app.platform.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.identity.internal.LockoutPolicy.Failure;
import app.platform.identity.internal.LockoutPolicy.State;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Stories S3-SEC-08 and S3-SEC-10: a progressive lock with a cap that recovers on its own and is no weapon. */
class LockoutPolicyTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private final LockoutPolicy policy = new LockoutPolicy(
            new IdentityProperties.Lockout(5, Duration.ofMinutes(1), Duration.ofMinutes(15), Duration.ofMinutes(15)));

    private Failure fail(State state, Instant at) {
        return policy.afterFailure(state, at);
    }

    private State failNTimes(int times, Instant start) {
        State state = State.CLEAN;
        for (int i = 0; i < times; i++) {
            state = fail(state, start.plusSeconds(i)).state();
        }
        return state;
    }

    @Test
    void fourFailuresDoNotLockTheFifthDoes() {
        State afterFour = failNTimes(4, T0);
        assertThat(afterFour.isLocked(T0.plusSeconds(10))).isFalse();

        Failure fifth = fail(afterFour, T0.plusSeconds(4));

        assertThat(fifth.lockLength()).isEqualTo(Duration.ofMinutes(1));
        assertThat(fifth.state().isLocked(T0.plusSeconds(5))).isTrue();
        assertThat(fifth.state().lockedUntil()).isEqualTo(T0.plusSeconds(4).plus(Duration.ofMinutes(1)));
    }

    @Test
    void theLockDoublesWithEachFurtherFailureAndStopsAtTheCap() {
        State state = failNTimes(5, T0);
        Instant now = state.lockedUntil().plusSeconds(1);
        long[] expectedMinutes = {2, 4, 8, 15, 15, 15};

        for (long expected : expectedMinutes) {
            Failure failure = fail(state, now);
            assertThat(failure.lockLength()).isEqualTo(Duration.ofMinutes(expected));
            state = failure.state();
            now = state.lockedUntil().plusSeconds(1);
        }
    }

    @Test
    void anAttemptDuringALockIsRefusedAndNotCountedSoItCannotExtendTheLock() {
        State locked = failNTimes(5, T0);
        Instant until = locked.lockedUntil();

        Failure during = fail(locked, T0.plusSeconds(30));

        assertThat(during.lockedNow()).isTrue();
        assertThat(during.state()).isEqualTo(locked);
        assertThat(during.state().lockedUntil()).isEqualTo(until);
    }

    @Test
    void theLockEndsByItself() {
        State locked = failNTimes(5, T0);

        assertThat(locked.isLocked(locked.lockedUntil().minusMillis(1))).isTrue();
        assertThat(locked.isLocked(locked.lockedUntil())).isFalse();
    }

    @Test
    void afterAQuietPeriodTheCountStartsAgain() {
        State locked = failNTimes(5, T0);
        // Quiet is measured from the end of the lock, not from the last failure.
        Instant quietOver = locked.lockedUntil().plus(Duration.ofMinutes(15)).plusSeconds(1);

        Failure failure = fail(locked, quietOver);

        assertThat(failure.state().failedAttempts()).isEqualTo(1);
        assertThat(failure.lockLength()).isNull();
    }

    @Test
    void aLockOfTheCapLengthDoesNotEraseTheCountBeforeTheNextFailure() {
        // Climb to the cap: an attacker who waits the lock out must meet the cap again, not start from one minute.
        State state = failNTimes(5, T0);
        for (int i = 0; i < 4; i++) {
            state = fail(state, state.lockedUntil().plusSeconds(1)).state();
        }

        assertThat(fail(state, state.lockedUntil().plusSeconds(1)).lockLength()).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void failuresFarApartNeverAccumulateIntoALock() {
        State state = State.CLEAN;
        Instant at = T0;
        for (int i = 0; i < 20; i++) {
            state = fail(state, at).state();
            at = at.plus(Duration.ofMinutes(16));
        }

        assertThat(state.failedAttempts()).isEqualTo(1);
        assertThat(state.isLocked(at)).isFalse();
    }
}
