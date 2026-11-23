package app.platform.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The secrets and identifiers of connected applications (Sprint 31, ADR-0268), without a database: full entropy, a
 * recognisable start, only a hash kept, a check that does not care where the first difference is, and nothing in a
 * printed form that could leak.
 */
class ClientSecretsTest {

    @Test
    void aSecretIsThirtyTwoRandomBytesWithAStartThatAScannerCanFind() {
        String secret = ClientSecrets.generate();

        assertThat(secret).startsWith("cs_").matches("cs_[A-Za-z0-9_-]{43}");
    }

    @Test
    void aThousandSecretsAreAllDifferent() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            seen.add(ClientSecrets.generate());
        }

        assertThat(seen).hasSize(1000);
    }

    @Test
    void aClientIdentifierHasTheShapeTheDatabaseChecksAndIsNotASecretWord() {
        for (int i = 0; i < 200; i++) {
            assertThat(ClientSecrets.newClientId()).matches("ca_[A-Za-z0-9]{24}");
        }
        assertThat(ClientSecrets.newClientId()).doesNotStartWith(ClientSecrets.PREFIX);
    }

    @Test
    void theStoredFormIsAMarkedHashThatTheDatabaseAccepts() {
        String secret = ClientSecrets.generate();

        String hash = ClientSecrets.hash(secret);

        assertThat(hash).matches("sha256:[0-9a-f]{64}").doesNotContain(secret);
        assertThat(ClientSecrets.hash(secret)).as("the same text always hashes the same").isEqualTo(hash);
        assertThat(ClientSecrets.hash(ClientSecrets.generate())).isNotEqualTo(hash);
    }

    @Test
    void theRightSecretMatchesAndAnyOtherTextDoesNot() {
        String secret = ClientSecrets.generate();
        String hash = ClientSecrets.hash(secret);

        assertThat(ClientSecrets.matches(secret, hash)).isTrue();
        assertThat(ClientSecrets.matches(secret + "x", hash)).isFalse();
        assertThat(ClientSecrets.matches(secret.substring(1), hash)).isFalse();
        assertThat(ClientSecrets.matches(ClientSecrets.generate(), hash)).isFalse();
        assertThat(ClientSecrets.matches("", hash)).isFalse();
        assertThat(ClientSecrets.matches(null, hash)).isFalse();
    }

    @Test
    void theStoredHashItselfIsNotAcceptedAsTheSecret() {
        String hash = ClientSecrets.hash(ClientSecrets.generate());

        assertThat(ClientSecrets.matches(hash, hash)).as("a copy of the table is not a key").isFalse();
    }

    @Test
    void aPresentedTextThatIsFarTooLongIsRefusedWithoutBeingHashed() {
        String secret = ClientSecrets.generate();

        assertThat(ClientSecrets.matches("x".repeat(100_000), ClientSecrets.hash(secret))).isFalse();
        assertThat(ClientSecrets.matchesNothing("x".repeat(100_000))).isFalse();
    }

    @Test
    void checkingAgainstNothingAlwaysSaysNo() {
        assertThat(ClientSecrets.matchesNothing(ClientSecrets.generate())).isFalse();
        assertThat(ClientSecrets.matchesNothing(null)).isFalse();
        assertThat(ClientSecrets.matchesNothing("no-such-secret")).isFalse();
    }

    @Test
    void theHashDoesNotDependOnWhereTheFirstDifferenceIs() {
        // Constant time cannot be measured reliably in a unit test; this holds the property that makes it possible:
        // both sides are compared as whole equal-length digests, never as the secrets themselves.
        String secret = ClientSecrets.generate();
        String hash = ClientSecrets.hash(secret);
        String first = "X" + secret.substring(1);
        String last = secret.substring(0, secret.length() - 1) + "X";

        assertThat(ClientSecrets.hash(first)).hasSameSizeAs(hash);
        assertThat(ClientSecrets.hash(last)).hasSameSizeAs(hash);
        assertThat(ClientSecrets.matches(first, hash)).isFalse();
        assertThat(ClientSecrets.matches(last, hash)).isFalse();
    }
}
