package app.platform.identity.internal;

import java.time.Duration;
import java.time.Instant;

/**
 * The progressive account lock (story S3-SEC-08, ADR-0021), as pure arithmetic so that it can be tested without a
 * database.
 *
 * <p>From {@code threshold} consecutive failures the account is locked for {@code base}; every further failure doubles
 * the lock, up to {@code cap}. The lock always ends by itself. Rules that keep the lock from becoming a weapon:
 * <ul>
 *   <li>An attempt made while the account is locked is refused and <em>not counted</em>, so hammering a locked account
 *       does not lengthen the lock.</li>
 *   <li>The counter starts again from zero after a success, or once {@code quiet} has passed since the later of the
 * last       failure and the end of the last lock (measured from the end of the lock, otherwise a cap-length lock would
 *       always erase the count and an attacker would never climb past the first step).</li>
 * </ul>
 */
final class LockoutPolicy {

    /** What the database holds about one account's recent failures. */
    record State(int failedAttempts, Instant lastFailedAt, Instant lockedUntil) {

        static final State CLEAN = new State(0, null, null);

        /** Whether the account is locked at {@code now}. */
        boolean isLocked(Instant now) {
            return lockedUntil != null && now.isBefore(lockedUntil);
        }
    }

    /**
     * The state after one more failure.
     *
     * @param state the state before the failure
     * @param lockedNow whether the lock was in force when the attempt was made
     * @param lockLength the length of the lock this failure caused, or null when it caused none
     */
    record Failure(State state, boolean lockedNow, Duration lockLength) {
    }

    private final int threshold;
    private final Duration base;
    private final Duration cap;
    private final Duration quiet;

    LockoutPolicy(IdentityProperties.Lockout settings) {
        this.threshold = settings.threshold();
        this.base = settings.base();
        this.cap = settings.cap();
        this.quiet = settings.quiet();
    }

    /** The state after a failed attempt at {@code now}. */
    Failure afterFailure(State current, Instant now) {
        if (current.isLocked(now)) {
            return new Failure(current, true, null);
        }
        int attempts = quietPeriodPassed(current, now) ? 0 : current.failedAttempts();
        attempts++;
        Instant lockedUntil = current.lockedUntil();
        Duration lockLength = null;
        if (attempts >= threshold) {
            lockLength = lockLengthFor(attempts - threshold);
            lockedUntil = now.plus(lockLength);
        }
        return new Failure(new State(attempts, now, lockedUntil), false, lockLength);
    }

    private boolean quietPeriodPassed(State state, Instant now) {
        Instant reference = state.lastFailedAt();
        if (state.lockedUntil() != null && (reference == null || state.lockedUntil().isAfter(reference))) {
            reference = state.lockedUntil();
        }
        return reference != null && now.isAfter(reference.plus(quiet));
    }

    private Duration lockLengthFor(int stepsOverThreshold) {
        Duration length = base;
        for (int i = 0; i < stepsOverThreshold && length.compareTo(cap) < 0; i++) {
            length = length.multipliedBy(2);
        }
        return length.compareTo(cap) > 0 ? cap : length;
    }
}
