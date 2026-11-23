package app.platform.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.testsupport.MutableClock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Failed authentication at the token endpoint is limited per source and per client identifier (Sprint 31, ADR-0268),
 * the same for an application that exists and one that does not, and a successful attempt costs nothing.
 */
class ClientAuthLimiterTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    private final ClientProperties.Failures limits = new ClientProperties.Failures(3, Duration.ofMinutes(10), 2,
            Duration.ofMinutes(1));

    private ClientAuthLimiter limiter() {
        return new ClientAuthLimiter(new LocalCounters(clock), limits);
    }

    @Test
    void aSourceMayFailOnlyUpToItsLimitAndAnotherSourceIsNotAffected() {
        ClientAuthLimiter limiter = limiter();
        for (int i = 0; i < 3; i++) {
            assertThat(limiter.allowed("203.0.113.9", "ca_" + i)).isTrue();
            limiter.recordFailure("203.0.113.9", "ca_" + i);
        }

        assertThat(limiter.allowed("203.0.113.9", "ca_new")).isFalse();
        assertThat(limiter.allowed("203.0.113.10", "ca_new")).isTrue();
    }

    @Test
    void aClientIdentifierMayFailOnlyUpToItsLimitWhateverTheSource() {
        ClientAuthLimiter limiter = limiter();
        limiter.recordFailure("203.0.113.1", "ca_target");
        limiter.recordFailure("203.0.113.2", "ca_target");

        assertThat(limiter.allowed("203.0.113.3", "ca_target")).isFalse();
        assertThat(limiter.allowed("203.0.113.3", "ca_other")).isTrue();
    }

    @Test
    void theOwnersWaitIsOnlyOneShortWindowLong() {
        ClientAuthLimiter limiter = limiter();
        limiter.recordFailure("203.0.113.1", "ca_target");
        limiter.recordFailure("203.0.113.2", "ca_target");
        assertThat(limiter.allowed("203.0.113.9", "ca_target")).isFalse();

        clock.advance(Duration.ofMinutes(1).plusSeconds(1));

        assertThat(limiter.allowed("203.0.113.9", "ca_target")).as("the window ended").isTrue();
    }

    @Test
    void aSourceIsFreeAgainWhenItsLongerWindowEnds() {
        ClientAuthLimiter limiter = limiter();
        for (int i = 0; i < 3; i++) {
            limiter.recordFailure("203.0.113.9", "ca_" + i);
        }
        assertThat(limiter.allowed("203.0.113.9", "ca_x")).isFalse();

        clock.advance(Duration.ofMinutes(10).plusSeconds(1));

        assertThat(limiter.allowed("203.0.113.9", "ca_x")).isTrue();
    }

    @Test
    void anIdentifierThatDoesNotExistIsCountedExactlyLikeOneThatDoes() {
        ClientAuthLimiter limiter = limiter();

        limiter.recordFailure("203.0.113.1", "not-even-shaped-like-an-identifier");
        limiter.recordFailure("203.0.113.2", "not-even-shaped-like-an-identifier");

        assertThat(limiter.allowed("203.0.113.3", "not-even-shaped-like-an-identifier")).isFalse();
    }

    @Test
    void anAttemptThatIsOnlyAskedAboutIsNeverCounted() {
        ClientAuthLimiter limiter = limiter();

        for (int i = 0; i < 50; i++) {
            assertThat(limiter.allowed("203.0.113.9", "ca_x")).isTrue();
        }
    }

    @Test
    void aCallerIsToldToWaitAsLongAsTheLongestWindowThatMayApply() {
        assertThat(limiter().retryAfter()).isEqualTo(Duration.ofMinutes(10));
    }
}
