package app.platform.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Stories S3-SEC-01 (Argon2id, per-hash salt), S3-SEC-02 (algorithm in the hash, upgrade) and the hashing bound. */
class PasswordHasherTest {

    private static final IdentityProperties.Password SETTINGS =
            new IdentityProperties.Password(12, 128, 1024, 2, 1, 4, Duration.ofSeconds(2));

    private static char[] pw(String text) {
        return text.toCharArray();
    }

    @Test
    void aHashIsArgon2idAndCarriesItsOwnParameters() {
        PasswordHasher hasher = new PasswordHasher(SETTINGS);

        String hash = hasher.hash(pw("correct horse battery staple"));

        assertThat(hash).startsWith("$argon2id$v=19$m=1024,t=2,p=1$");
        assertThat(hash).doesNotContain("correct horse");
    }

    @Test
    void everyHashHasItsOwnSalt() {
        PasswordHasher hasher = new PasswordHasher(SETTINGS);

        assertThat(hasher.hash(pw("same password twice"))).isNotEqualTo(hasher.hash(pw("same password twice")));
    }

    @Test
    void theRightPasswordVerifiesAndAWrongOneDoesNot() {
        PasswordHasher hasher = new PasswordHasher(SETTINGS);
        String hash = hasher.hash(pw("correct horse battery staple"));

        assertThat(hasher.matches(pw("correct horse battery staple"), hash)).isTrue();
        assertThat(hasher.matches(pw("correct horse battery stapl"), hash)).isFalse();
        assertThat(hasher.matches(pw(""), hash)).isFalse();
    }

    @Test
    void theSamePasswordInAnotherUnicodeFormStillMatches() {
        PasswordHasher hasher = new PasswordHasher(SETTINGS);
        // One accented character, and the same letter plus a combining accent: the same text typed on two keyboards.
        String hash = hasher.hash(pw("café au lait long enough"));

        assertThat(hasher.matches(pw("café au lait long enough"), hash)).isTrue();
    }

    @Test
    void aHashMadeWithOtherParametersStillVerifiesAndIsMarkedForUpgrade() {
        String old = new PasswordHasher(new IdentityProperties.Password(12, 128, 512, 1, 1, 4, Duration.ofSeconds(2)))
                .hash(pw("an older hash of this password"));
        PasswordHasher current = new PasswordHasher(SETTINGS);

        assertThat(current.matches(pw("an older hash of this password"), old)).isTrue();
        assertThat(current.needsUpgrade(old)).isTrue();
        assertThat(current.needsUpgrade(current.hash(pw("a new hash of this password")))).isFalse();
    }

    @Test
    void aLegacyBcryptHashVerifiesAndAlwaysNeedsAnUpgrade() {
        PasswordHasher hasher = new PasswordHasher(SETTINGS);
        String legacy = hasher.legacyHashForTests(pw("a password from an older system"));

        assertThat(legacy).startsWith("$2");
        assertThat(hasher.matches(pw("a password from an older system"), legacy)).isTrue();
        assertThat(hasher.matches(pw("another password entirely"), legacy)).isFalse();
        assertThat(hasher.needsUpgrade(legacy)).isTrue();
    }

    @Test
    void anUnknownHashFormatNeverMatches() {
        PasswordHasher hasher = new PasswordHasher(SETTINGS);

        assertThat(hasher.matches(pw("anything at all"), "plain text, not a hash")).isFalse();
        assertThat(hasher.needsUpgrade("plain text, not a hash")).isTrue();
    }

    @Test
    void checkingAgainstNothingDoesTheWorkAndNeverMatches() {
        PasswordHasher hasher = new PasswordHasher(SETTINGS);

        assertThat(hasher.matchesNothing(pw("whatever the person typed"))).isFalse();
    }

    @Test
    void onlyAFewHashesRunAtOnceAndTheOthersAreRefusedInsteadOfEatingMemory() throws Exception {
        // One slot, held by a costly hash while a second request waits much less than that hash takes.
        IdentityProperties.Password slow = new IdentityProperties.Password(12, 128, 65_536, 4, 1, 1,
                Duration.ofMillis(50));
        PasswordHasher hasher = new PasswordHasher(slow);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch started = new CountDownLatch(1);
        try {
            Future<String> first = pool.submit(() -> {
                started.countDown();
                return hasher.hash(pw("the first one holds the only slot"));
            });
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> {
                // At least one attempt made while the first hash still runs has to be refused.
                for (int i = 0; i < 200 && !first.isDone(); i++) {
                    hasher.hash(pw("this one has to wait"));
                }
                throw new PasswordHasher.HashingBusyException();
            }).isInstanceOf(PasswordHasher.HashingBusyException.class);
            first.get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }
}
