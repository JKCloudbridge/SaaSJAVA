package app.platform.integration.internal;

import java.time.Duration;
import java.time.Instant;

/**
 * The token bucket that limits how fast one connection sends (Sprint 29, ADR-0232), as a pure function. The bucket is
 * kept in the database row of the connection and handled under its lock, so every instance draws from the same one.
 * A send that finds no token is not failed: it waits for the time the bucket says (a throttled delivery waits).
 *
 * <p>The bucket holds at most one second's worth of sends, and at least one, so a quiet connection can send a short
 * burst and a slow one still sends.
 */
final class RateLimiter {

    /**
     * The bucket.
     *
     * @param tokens how many sends are available, a fraction while it refills
     * @param at when that was counted
     */
    record Bucket(double tokens, Instant at) {
    }

    /**
     * The result of asking for a token.
     *
     * @param admitted whether a token was taken
     * @param bucket the bucket to store
     * @param delay how long until a token will be there, zero when one was taken
     */
    record Take(boolean admitted, Bucket bucket, Duration delay) {
    }

    private RateLimiter() {
    }

    /** The most tokens the bucket holds for this rate. */
    static double capacity(int perMinute) {
        return Math.max(1.0, Math.ceil(perMinute / 60.0));
    }

    /** A bucket that starts full. */
    static Bucket full(int perMinute, Instant now) {
        return new Bucket(capacity(perMinute), now);
    }

    /** Takes one token if there is one. */
    static Take take(Bucket bucket, Instant now, int perMinute) {
        double perSecond = perMinute / 60.0;
        double capacity = capacity(perMinute);
        double elapsed = Math.max(0, Duration.between(bucket.at(), now).toNanos() / 1_000_000_000.0);
        double tokens = Math.min(capacity, Math.max(0, bucket.tokens()) + elapsed * perSecond);
        if (tokens >= 1.0) {
            return new Take(true, new Bucket(tokens - 1.0, now), Duration.ZERO);
        }
        long millis = (long) Math.ceil((1.0 - tokens) / perSecond * 1000.0);
        return new Take(false, new Bucket(tokens, now), Duration.ofMillis(Math.max(1, millis)));
    }
}
