package app.platform.outbox.internal;

import java.time.Duration;
import java.util.random.RandomGenerator;

/**
 * How long to wait before the next attempt: exponential from {@code initial}, capped at {@code max}, with random
 * jitter of plus or minus 20 percent so that events that failed together do not all retry at the same moment.
 */
final class Backoff {

    private static final double JITTER = 0.2;

    private final Duration initial;
    private final Duration max;
    private final RandomGenerator random;

    Backoff(Duration initial, Duration max, RandomGenerator random) {
        this.initial = initial;
        this.max = max;
        this.random = random;
    }

    /**
     * @param failedAttempt the attempt that just failed, starting at 1
     * @return the wait before the next attempt
     */
    Duration delayAfter(int failedAttempt) {
        double seconds = initial.toMillis() / 1000.0;
        double cap = max.toMillis() / 1000.0;
        // Doubling per attempt; the exponent is bounded so the multiplication cannot overflow before the cap applies.
        double base = Math.min(cap, seconds * Math.pow(2, Math.min(failedAttempt - 1, 30)));
        double jittered = base * (1 - JITTER + random.nextDouble() * 2 * JITTER);
        return Duration.ofMillis(Math.max(1, Math.round(Math.min(cap, jittered) * 1000)));
    }
}
