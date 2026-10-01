package app.platform.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.identity.internal.SignInLimiter.Verdict;
import app.platform.testsupport.MutableClock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Story S3-SEC-09: limits per source and per identifier, the same for accounts that exist and ones that do not. */
class SignInLimiterTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    private final IdentityProperties.RateLimit limits = new IdentityProperties.RateLimit(
            3, Duration.ofMinutes(10), 8, Duration.ofMinutes(1), 4, Duration.ofMinutes(1),
            Duration.ofMillis(250), Duration.ofSeconds(5));

    private SignInLimiter limiter() {
        return new SignInLimiter(new LocalCounters(clock), limits);
    }

    @Test
    void aSourceMayFailOnlyUpToItsLimitThenIsRefusedBeforeAnyPasswordWork() {
        SignInLimiter limiter = limiter();
        for (int i = 0; i < 3; i++) {
            assertThat(limiter.admit("203.0.113.9", "user-" + i + "@example.test").allowed()).isTrue();
            limiter.recordFailure("203.0.113.9");
        }

        assertThat(limiter.admit("203.0.113.9", "user-9@example.test")).isEqualTo(Verdict.SOURCE_FAILURES);
        // Another source is not affected by it.
        assertThat(limiter.admit("203.0.113.10", "user-9@example.test")).isEqualTo(Verdict.ALLOWED);
    }

    @Test
    void sprayingOnePasswordOverManyAccountsFromOneSourceIsStoppedBySourceNotByAccount() {
        SignInLimiter limiter = limiter();
        int allowed = 0;
        for (int i = 0; i < 50; i++) {
            if (limiter.admit("198.51.100.7", "victim-" + i + "@example.test").allowed()) {
                allowed++;
                limiter.recordFailure("198.51.100.7");
            }
        }

        assertThat(allowed).isEqualTo(3);
    }

    @Test
    void anIdentifierIsLimitedAcrossSourcesAndForUnknownAccountsExactlyAsForKnownOnes() {
        SignInLimiter limiter = limiter();
        Verdict last = Verdict.ALLOWED;
        for (int i = 0; i < 6; i++) {
            last = limiter.admit("192.0.2." + i, "Nobody-Here@Example.test");
        }

        assertThat(last).isEqualTo(Verdict.IDENTIFIER_ATTEMPTS);
        // The identifier is compared in its normalized form: another spelling is the same bucket.
        assertThat(limiter.admit("192.0.2.77", "  nobody-here@example.test ")).isEqualTo(Verdict.IDENTIFIER_ATTEMPTS);
    }

    @Test
    void theIdentifierLimitEndsWithItsShortWindowSoItCannotKeepTheOwnerOutForLong() {
        SignInLimiter limiter = limiter();
        for (int i = 0; i < 6; i++) {
            limiter.admit("192.0.2." + i, "owner@example.test");
        }
        assertThat(limiter.admit("192.0.2.99", "owner@example.test")).isEqualTo(Verdict.IDENTIFIER_ATTEMPTS);

        clock.advance(Duration.ofMinutes(1));

        assertThat(limiter.admit("192.0.2.99", "owner@example.test")).isEqualTo(Verdict.ALLOWED);
    }

    @Test
    void theAttemptLimitBoundsTheWorkOneSourceCanCause() {
        SignInLimiter limiter = limiter();
        Verdict last = Verdict.ALLOWED;
        for (int i = 0; i < 9; i++) {
            last = limiter.admit("203.0.113.50", "someone-" + i + "@example.test");
        }

        assertThat(last).isEqualTo(Verdict.SOURCE_ATTEMPTS);
    }

    @Test
    void theAdvisedWaitMatchesTheWindowThatRefused() {
        SignInLimiter limiter = limiter();

        assertThat(limiter.retryAfter(Verdict.IDENTIFIER_ATTEMPTS)).isEqualTo(Duration.ofMinutes(1));
        assertThat(limiter.retryAfter(Verdict.SOURCE_FAILURES)).isEqualTo(Duration.ofMinutes(10));
    }
}
