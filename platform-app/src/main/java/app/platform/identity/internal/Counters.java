package app.platform.identity.internal;

import java.time.Duration;

/**
 * Fixed-window counters used by the rate limits (ADR-0021). A counter starts at one on its first increment and
 * disappears when its window ends. Shared across instances when backed by Redis.
 */
interface Counters {

    /** Adds one and returns the new count; the first increment starts the window. */
    long increment(String key, Duration window);

    /** The current count, zero when there is none. Does not change anything. */
    long current(String key);
}
