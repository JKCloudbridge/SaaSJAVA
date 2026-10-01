package app.platform.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.MutableClock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** The counters behind the rate limits: windows, and the fall-back to this instance's memory when Redis fails. */
class CountersTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));

    @Test
    void aWindowCountsUpAndStartsAgainWhenItEnds() {
        LocalCounters counters = new LocalCounters(clock);

        assertThat(counters.increment("k", Duration.ofMinutes(1))).isEqualTo(1);
        assertThat(counters.increment("k", Duration.ofMinutes(1))).isEqualTo(2);
        assertThat(counters.current("k")).isEqualTo(2);

        clock.advance(Duration.ofMinutes(1));

        assertThat(counters.current("k")).isZero();
        assertThat(counters.increment("k", Duration.ofMinutes(1))).isEqualTo(1);
    }

    @Test
    void keysDoNotInterfere() {
        LocalCounters counters = new LocalCounters(clock);

        counters.increment("a", Duration.ofMinutes(1));

        assertThat(counters.current("b")).isZero();
    }

    /** A shared store that can be switched off, counting how often it is asked. */
    private static final class FlakyShared implements Counters {

        private final LocalCounters inner;
        boolean down;
        int calls;

        FlakyShared(MutableClock clock) {
            this.inner = new LocalCounters(clock);
        }

        @Override
        public long increment(String key, Duration window) {
            calls++;
            if (down) {
                throw new IllegalStateException("connection details that must not be logged: redis://secret@host");
            }
            return inner.increment(key, window);
        }

        @Override
        public long current(String key) {
            calls++;
            if (down) {
                throw new IllegalStateException("down");
            }
            return inner.current(key);
        }
    }

    @Test
    void whileRedisWorksTheSharedCounterIsUsed() {
        FlakyShared shared = new FlakyShared(clock);
        ResilientCounters counters = new ResilientCounters(shared, new LocalCounters(clock), clock,
                Duration.ofSeconds(5), new SimpleMeterRegistry());

        counters.increment("k", Duration.ofMinutes(1));
        counters.increment("k", Duration.ofMinutes(1));

        assertThat(shared.current("k")).isEqualTo(2);
    }

    @Test
    void whenRedisFailsTheInstanceCountsForItselfAndSignInKeepsWorking() {
        FlakyShared shared = new FlakyShared(clock);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ResilientCounters counters = new ResilientCounters(shared, new LocalCounters(clock), clock,
                Duration.ofSeconds(5), meters);
        shared.down = true;

        assertThat(counters.increment("k", Duration.ofMinutes(1))).isEqualTo(1);
        assertThat(counters.increment("k", Duration.ofMinutes(1))).isEqualTo(2);
        assertThat(counters.current("k")).isEqualTo(2);
        assertThat(meters.get("platform.identity.ratelimit.degraded").counter().count()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void aBrokenRedisIsNotAskedAgainDuringThePauseSoRequestsAreNotSlowed() {
        FlakyShared shared = new FlakyShared(clock);
        ResilientCounters counters = new ResilientCounters(shared, new LocalCounters(clock), clock,
                Duration.ofSeconds(5), new SimpleMeterRegistry());
        shared.down = true;

        counters.increment("k", Duration.ofMinutes(1));
        int callsAfterFailure = shared.calls;
        for (int i = 0; i < 20; i++) {
            counters.increment("k", Duration.ofMinutes(1));
        }

        assertThat(shared.calls).isEqualTo(callsAfterFailure);
    }

    @Test
    void whenThePauseEndsAndRedisIsBackTheSharedCounterIsUsedAgain() {
        FlakyShared shared = new FlakyShared(clock);
        ResilientCounters counters = new ResilientCounters(shared, new LocalCounters(clock), clock,
                Duration.ofSeconds(5), new SimpleMeterRegistry());
        shared.down = true;
        counters.increment("k", Duration.ofMinutes(1));

        shared.down = false;
        clock.advance(Duration.ofSeconds(6));
        counters.increment("other", Duration.ofMinutes(1));

        assertThat(shared.current("other")).isEqualTo(1);
    }
}
