package spike.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import spike.auth.AuthenticationAttempt.PasswordAttempt;
import spike.auth.AuthenticationOutcome.Authenticated;
import spike.auth.AuthenticationOutcome.Reason;
import spike.auth.AuthenticationOutcome.Rejected;
import spike.auth.SpikeSupport.CountingEncoder;
import spike.auth.SpikeSupport.MutableClock;

/** Question 2: hashing, enumeration and brute-force protection of the local provider. */
class LocalCredentialsTest {

    private final MutableClock clock = new MutableClock();
    private CountingEncoder encoder;
    private LocalCredentialsProvider provider;

    @BeforeEach
    void setUp() {
        encoder = new CountingEncoder(SpikeSupport.delegatingEncoder());
        provider = new LocalCredentialsProvider(encoder, clock);
        provider.register("user-a@example.test", "right-password");
    }

    @Test
    void newHashesUseArgon2idWithAPerHashSalt() {
        provider.register("user-b@example.test", "right-password");

        String hashA = provider.find("user-a@example.test").passwordHash();
        String hashB = provider.find("user-b@example.test").passwordHash();

        assertThat(hashA).startsWith("{argon2}");
        assertThat(hashA).isNotEqualTo(hashB); // same password, different salt
    }

    @Test
    void hashingCostIsInAUsableRange() {
        long start = System.nanoTime();
        encoder.encode("some-password");
        long millis = (System.nanoTime() - start) / 1_000_000;

        // Too fast means too weak; too slow means a login DoS vector. Final tuning happens in Sprint 3.
        assertThat(millis).as("argon2 hash time in ms").isBetween(5L, 3_000L);
    }

    @Test
    void unknownAccountsCostTheSameVerificationAsKnownAccounts() {
        int before = encoder.matchCalls.get();
        provider.authenticate(new PasswordAttempt("nobody@example.test", "x"));
        int unknown = encoder.matchCalls.get() - before;

        before = encoder.matchCalls.get();
        provider.authenticate(new PasswordAttempt("user-a@example.test", "wrong"));
        int known = encoder.matchCalls.get() - before;

        assertThat(unknown).isEqualTo(1).isEqualTo(known);
    }

    @Test
    void responseTimeDoesNotRevealWhetherTheAccountExists() {
        List<Long> unknown = new ArrayList<>();
        List<Long> known = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            unknown.add(time(() -> provider.authenticate(new PasswordAttempt("nobody@example.test", "x"))));
            known.add(time(() -> provider.authenticate(new PasswordAttempt("user-a@example.test", "wrong"))));
            clock.advance(Duration.ofHours(1)); // keep lockout out of the measurement
            provider.find("user-a@example.test");
        }
        double ratio = (double) median(unknown) / median(known);

        // Generous bound: this guards against an early return, not against microsecond differences.
        assertThat(ratio).as("median unknown/known time ratio").isBetween(0.4, 2.5);
    }

    @Test
    void accountLocksAfterRepeatedFailuresEvenForTheRightPasswordAndRecoversAfterTheWindow() {
        for (int i = 0; i < LocalCredentialsProvider.LOCK_AFTER_FAILURES; i++) {
            provider.authenticate(new PasswordAttempt("user-a@example.test", "wrong"));
        }

        assertThat(provider.authenticate(new PasswordAttempt("user-a@example.test", "right-password")))
                .isEqualTo(new Rejected(Reason.LOCKED));

        clock.advance(LocalCredentialsProvider.BASE_LOCK.plusSeconds(1));
        assertThat(provider.authenticate(new PasswordAttempt("user-a@example.test", "right-password")))
                .isInstanceOf(Authenticated.class);
        assertThat(provider.find("user-a@example.test").failedAttempts()).isZero();
    }

    @Test
    void lockoutGrowsWithContinuedAttacksAndIsCapped() {
        for (int i = 0; i < LocalCredentialsProvider.LOCK_AFTER_FAILURES; i++) {
            provider.authenticate(new PasswordAttempt("user-a@example.test", "wrong"));
        }
        var account = provider.find("user-a@example.test");
        var firstLock = Duration.between(clock.instant(), account.lockedUntil());

        clock.advance(firstLock.plusSeconds(1));
        provider.authenticate(new PasswordAttempt("user-a@example.test", "wrong")); // 6th failure
        var secondLock = Duration.between(clock.instant(), account.lockedUntil());

        assertThat(secondLock).isGreaterThan(firstLock);
        for (int i = 0; i < 30; i++) {
            clock.advance(Duration.ofMinutes(16));
            provider.authenticate(new PasswordAttempt("user-a@example.test", "wrong"));
        }
        assertThat(Duration.between(clock.instant(), account.lockedUntil()))
                .isLessThanOrEqualTo(LocalCredentialsProvider.MAX_LOCK);
    }

    @Test
    void identifiersAreNormalised() {
        assertThat(provider.authenticate(new PasswordAttempt("  User-A@Example.TEST ", "right-password")))
                .isInstanceOf(Authenticated.class);
    }

    @Test
    void legacyBcryptHashesVerifyAndAreUpgradedToArgon2OnLogin() {
        String legacy = "{bcrypt}" + new BCryptPasswordEncoder().encode("right-password");
        provider.overrideHash("user-a@example.test", legacy);

        assertThat(provider.authenticate(new PasswordAttempt("user-a@example.test", "right-password")))
                .isInstanceOf(Authenticated.class);
        assertThat(provider.find("user-a@example.test").passwordHash()).startsWith("{argon2}");
    }

    private static long time(Runnable action) {
        long start = System.nanoTime();
        action.run();
        return System.nanoTime() - start;
    }

    private static long median(List<Long> values) {
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        return sorted.get(sorted.size() / 2);
    }
}
