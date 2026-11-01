package app.platform.integration.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.integration.internal.RateLimiter.Bucket;
import app.platform.integration.internal.RateLimiter.Take;
import java.time.Duration;
import java.time.Instant;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

/** The token bucket of a connection (Sprint 29, ADR-0232): a throttled send waits, it never fails. */
class RateLimiterTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void theBucketHoldsAtMostOneSecondOfSendsAndAtLeastOne() {
        assertThat(RateLimiter.capacity(6000)).isEqualTo(100.0);
        assertThat(RateLimiter.capacity(60)).isEqualTo(1.0);
        assertThat(RateLimiter.capacity(1)).isEqualTo(1.0);
    }

    @Test
    void aFullBucketGivesItsBurstAndThenTellsHowLongToWait() {
        Bucket bucket = RateLimiter.full(120, T0);
        int taken = 0;
        Take take = RateLimiter.take(bucket, T0, 120);
        while (take.admitted()) {
            taken++;
            bucket = take.bucket();
            take = RateLimiter.take(bucket, T0, 120);
        }

        assertThat(taken).isEqualTo(2);
        assertThat(take.delay()).isBetween(Duration.ofMillis(1), Duration.ofMillis(501));
    }

    @Test
    void waitingForTheTimeTheBucketSaidGivesAToken() {
        Bucket empty = new Bucket(0, T0);
        Take refused = RateLimiter.take(empty, T0, 60);

        Take later = RateLimiter.take(refused.bucket(), T0.plus(refused.delay()), 60);

        assertThat(refused.admitted()).isFalse();
        assertThat(later.admitted()).isTrue();
    }

    @Property
    void overAnyRunTheSendsAdmittedNeverExceedTheBurstPlusTheRateTimesTheTime(
            @ForAll @IntRange(min = 1, max = 3000) int perMinute,
            @ForAll @IntRange(min = 1, max = 200) int asks,
            @ForAll @IntRange(min = 0, max = 2000) int gapMillis) {
        Bucket bucket = RateLimiter.full(perMinute, T0);
        Instant now = T0;
        int admitted = 0;
        for (int i = 0; i < asks; i++) {
            Take take = RateLimiter.take(bucket, now, perMinute);
            bucket = take.bucket();
            if (take.admitted()) {
                admitted++;
            }
            assertThat(bucket.tokens()).isBetween(0.0, RateLimiter.capacity(perMinute));
            now = now.plusMillis(gapMillis);
        }
        double seconds = Duration.between(T0, now).toMillis() / 1000.0;
        assertThat((double) admitted).isLessThanOrEqualTo(RateLimiter.capacity(perMinute) + perMinute / 60.0 * seconds
                + 1e-6);
    }
}
