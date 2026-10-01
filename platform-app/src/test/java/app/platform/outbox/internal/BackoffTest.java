package app.platform.outbox.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;

class BackoffTest {

    private static final RandomGenerator MIDDLE = new FixedRandom(0.5);

    @Test
    void doublesWithEveryFailedAttemptUntilTheCap() {
        Backoff backoff = new Backoff(Duration.ofSeconds(5), Duration.ofMinutes(15), MIDDLE);

        assertThat(backoff.delayAfter(1)).isEqualTo(Duration.ofSeconds(5));
        assertThat(backoff.delayAfter(2)).isEqualTo(Duration.ofSeconds(10));
        assertThat(backoff.delayAfter(3)).isEqualTo(Duration.ofSeconds(20));
        assertThat(backoff.delayAfter(4)).isEqualTo(Duration.ofSeconds(40));
        assertThat(backoff.delayAfter(8)).isEqualTo(Duration.ofSeconds(640));
        assertThat(backoff.delayAfter(9)).as("capped").isEqualTo(Duration.ofMinutes(15));
        assertThat(backoff.delayAfter(1000)).as("no overflow").isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void jitterStaysWithinTwentyPercentAndNeverExceedsTheCap() {
        Backoff low = new Backoff(Duration.ofSeconds(10), Duration.ofMinutes(15), new FixedRandom(0.0));
        Backoff high = new Backoff(Duration.ofSeconds(10), Duration.ofMinutes(15), new FixedRandom(0.999999));
        Backoff capped = new Backoff(Duration.ofSeconds(10), Duration.ofSeconds(10), new FixedRandom(0.999999));

        assertThat(low.delayAfter(1)).isEqualTo(Duration.ofSeconds(8));
        assertThat(high.delayAfter(1)).isBetween(Duration.ofMillis(11_990), Duration.ofSeconds(12));
        assertThat(capped.delayAfter(1)).isLessThanOrEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void theDelayIsAlwaysPositive() {
        Backoff backoff = new Backoff(Duration.ofMillis(1), Duration.ofMillis(1), new FixedRandom(0.0));

        assertThat(backoff.delayAfter(1)).isPositive();
    }

    /** A "random" generator that always answers the same fraction. */
    private record FixedRandom(double value) implements RandomGenerator {

        @Override
        public long nextLong() {
            throw new UnsupportedOperationException();
        }

        @Override
        public double nextDouble() {
            return value;
        }
    }
}
