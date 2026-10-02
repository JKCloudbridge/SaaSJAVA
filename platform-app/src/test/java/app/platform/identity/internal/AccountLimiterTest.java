package app.platform.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.platform.identity.internal.AccountLimiter.Kind;
import app.platform.testsupport.MutableClock;
import app.platformapi.ApiException;
import app.platformapi.ErrorCode;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The limits of sign-up, reset and link attempts (ADR-0023): they count every address alike and end with a window. */
class AccountLimiterTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    private final IdentityProperties.Account limits = new IdentityProperties.Account(
            Duration.ofHours(24), Duration.ofMinutes(60), 2, 3, Duration.ofHours(1), 4, Duration.ofHours(1), 5,
            Duration.ofMinutes(10), 3, 5, Duration.ofHours(24), Duration.ofDays(7), 2, 2, Duration.ofSeconds(60));

    private AccountLimiter limiter() {
        return new AccountLimiter(new LocalCounters(clock), limits);
    }

    private static void assertRefused(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.code()).isEqualTo(ErrorCode.RATE_LIMITED);
            assertThat(e.retryAfterSeconds()).isPositive();
        });
    }

    @Test
    void aSourceMayStartOnlyAsManySignUpsAsTheLimitSaysWhateverTheAddress() {
        AccountLimiter limiter = limiter();
        limiter.admitRequest(Kind.SIGN_UP, "203.0.113.9", "a@example.test");
        limiter.admitRequest(Kind.SIGN_UP, "203.0.113.9", "b@example.test");

        assertRefused(() -> limiter.admitRequest(Kind.SIGN_UP, "203.0.113.9", "c@example.test"));
        limiter.admitRequest(Kind.SIGN_UP, "203.0.113.10", "c@example.test");
    }

    @Test
    void resetsAndSignUpsAreCountedApartPerSource() {
        AccountLimiter limiter = limiter();
        limiter.admitRequest(Kind.SIGN_UP, "203.0.113.9", "a@example.test");
        limiter.admitRequest(Kind.SIGN_UP, "203.0.113.9", "b@example.test");

        limiter.admitRequest(Kind.PASSWORD_RESET, "203.0.113.9", "c@example.test");
    }

    @Test
    void anAddressCanBeMailedOnlyAsOftenAsTheLimitSaysFromAnySourceAndForAnyKind() {
        AccountLimiter limiter = limiter();
        limiter.admitRequest(Kind.SIGN_UP, "203.0.113.1", "a@example.test");
        limiter.admitRequest(Kind.PASSWORD_RESET, "203.0.113.2", "A@Example.test");
        limiter.admitRequest(Kind.SIGN_UP, "203.0.113.3", "a@example.test");
        limiter.admitRequest(Kind.PASSWORD_RESET, "203.0.113.4", "a@example.test");

        assertRefused(() -> limiter.admitRequest(Kind.PASSWORD_RESET, "203.0.113.5", "a@example.test"));
        limiter.admitRequest(Kind.PASSWORD_RESET, "203.0.113.5", "other@example.test");
    }

    @Test
    void theAllowanceComesBackWhenTheWindowEnds() {
        AccountLimiter limiter = limiter();
        limiter.admitRequest(Kind.SIGN_UP, "203.0.113.9", "a@example.test");
        limiter.admitRequest(Kind.SIGN_UP, "203.0.113.9", "b@example.test");
        assertRefused(() -> limiter.admitRequest(Kind.SIGN_UP, "203.0.113.9", "c@example.test"));

        clock.advance(Duration.ofHours(1).plusSeconds(1));

        limiter.admitRequest(Kind.SIGN_UP, "203.0.113.9", "c@example.test");
    }

    @Test
    void linkAttemptsAreLimitedPerSource() {
        AccountLimiter limiter = limiter();
        for (int i = 0; i < 5; i++) {
            limiter.admitTokenAttempt("203.0.113.9");
        }

        assertRefused(() -> limiter.admitTokenAttempt("203.0.113.9"));
        limiter.admitTokenAttempt("203.0.113.10");
    }

    @Test
    void foundingsAreLimitedPerPerson() {
        AccountLimiter limiter = limiter();
        UUID person = UUID.randomUUID();
        for (int i = 0; i < 5; i++) {
            limiter.admitFounding(person);
        }

        assertRefused(() -> limiter.admitFounding(person));
        limiter.admitFounding(UUID.randomUUID());
    }
}
