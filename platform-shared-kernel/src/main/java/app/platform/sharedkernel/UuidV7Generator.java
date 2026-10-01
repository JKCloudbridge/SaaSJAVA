package app.platform.sharedkernel;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * Generates time-ordered version 7 UUIDs (RFC 9562, method 1: a counter in the first random field).
 *
 * <p>Identifiers sort by creation time, which keeps B-tree indexes append-mostly, and stay strictly increasing
 * within one generator even when many are requested in the same millisecond or the clock steps backwards.
 * Thread safe.
 */
public final class UuidV7Generator {

    private static final int COUNTER_BITS = 12;
    private static final int COUNTER_MAX = (1 << COUNTER_BITS) - 1;
    private static final long VERSION_7 = 0x7L << 12;
    private static final long VARIANT = 0x2L << 62;
    private static final long RANDOM_62_MASK = (1L << 62) - 1;

    private final Object lock = new Object();
    private final Clock clock;
    private final RandomGenerator random;

    private long lastMillis = Long.MIN_VALUE;
    private int counter;

    /**
     * Creates a generator.
     *
     * @param clock source of the millisecond timestamp
     * @param random source of the random bits; use a cryptographically strong one in production
     */
    public UuidV7Generator(Clock clock, RandomGenerator random) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.random = Objects.requireNonNull(random, "random");
    }

    /** A generator on the system clock with a cryptographically strong random source. */
    public static UuidV7Generator system() {
        return new UuidV7Generator(Clock.systemUTC(), new SecureRandom());
    }

    /** Returns the next identifier, strictly greater than every identifier this generator returned before. */
    public UUID next() {
        long mostSignificant;
        synchronized (lock) {
            long now = clock.millis();
            if (now > lastMillis) {
                lastMillis = now;
                // Start below the maximum so a burst within one millisecond has room to count up.
                counter = random.nextInt(COUNTER_MAX / 2);
            } else if (counter < COUNTER_MAX) {
                counter++;
            } else {
                // Counter exhausted within this millisecond (or the clock moved back): borrow the next millisecond.
                lastMillis++;
                counter = 0;
            }
            mostSignificant = (lastMillis << 16) | VERSION_7 | counter;
        }
        long leastSignificant = VARIANT | (random.nextLong() & RANDOM_62_MASK);
        return new UUID(mostSignificant, leastSignificant);
    }
}
