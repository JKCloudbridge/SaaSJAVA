package app.platform.identity.internal;

import java.time.Duration;

/**
 * Fixed-window counters used by the rate limits (ADR-0021). A counter starts at one on its first increment and
 * disappears when its window ends. Shared across instances when backed by Redis.
 *
 * <p>Sprint 32 (ADR-0281) adds what the limits on the public API need: adding more than one (a bulk call costs its
 * records) and asking how long a window has left (the headers say when the count starts again). Both have a default
 * so that a counter that only knows the first two operations still works.
 */
interface Counters {

    /** Adds one and returns the new count; the first increment starts the window. */
    long increment(String key, Duration window);

    /** The current count, zero when there is none. Does not change anything. */
    long current(String key);

    /**
     * Adds {@code amount} (at least 1) and returns the new count; the first addition starts the window. The default
     * counts one at a time.
     */
    default long add(String key, Duration window, long amount) {
        long count = 0;
        for (long i = 0; i < amount; i++) {
            count = increment(key, window);
        }
        return count;
    }

    /** How long the window of the counter has left, or zero when there is none. Does not change anything. */
    default long remainingMillis(String key) {
        return 0;
    }
}
