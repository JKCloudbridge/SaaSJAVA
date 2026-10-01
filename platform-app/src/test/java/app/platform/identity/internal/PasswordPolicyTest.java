package app.platform.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Story S3-SEC-03: minimum and maximum length, no composition theatre, a list of common passwords, no address. */
class PasswordPolicyTest {

    private final PasswordPolicy policy = new PasswordPolicy(
            new IdentityProperties.Password(12, 64, 1024, 2, 1, 4, Duration.ofSeconds(2)),
            new BundledCommonPasswords());

    @Test
    void aLongEnoughPasswordWithoutAnySymbolIsAccepted() {
        // No composition rules: lower-case words only is fine when long enough and not common.
        assertThat(policy.violations("purple elephant dances slowly".toCharArray(), "user-a@example.test")).isEmpty();
    }

    @Test
    void aShortPasswordIsRefusedWithAMessageThatNamesTheMinimum() {
        assertThat(policy.violations("Sh0rt!pw".toCharArray(), null))
                .containsExactly("Must have at least 12 characters.");
    }

    @Test
    void anEnormousPasswordIsRefusedBeforeAnyHashingHappens() {
        assertThat(policy.violations("x".repeat(65).toCharArray(), null))
                .containsExactly("Must have at most 64 characters.");
    }

    @Test
    void theLengthCountsCharactersNotBytes() {
        // Twelve characters, each of several bytes.
        char[] twelve = "üñîçødéüñîçø".toCharArray();

        assertThat(policy.violations(twelve, null)).isEmpty();
    }

    @Test
    void aCommonPasswordIsRefusedEvenWhenLongEnoughAfterAddingDigits() {
        assertThat(policy.violations("password123456".toCharArray(), null))
                .anyMatch(message -> message.contains("common"));
        assertThat(policy.violations("PASSWORDPASSWORD".toCharArray(), null))
                .anyMatch(message -> message.contains("common"));
    }

    @Test
    void aPasswordBuiltFromTheAddressIsRefused() {
        assertThat(policy.violations("my-user-a-secret-1".toCharArray(), "user-a@example.test"))
                .contains("Must not contain your email address.");
        assertThat(policy.violations("ab-secret-12345".toCharArray(), "ab@example.test")).isEmpty();
    }

    @Test
    void noMessageRepeatsThePassword() {
        String candidate = "password1234";

        assertThat(policy.violations(candidate.toCharArray(), "password1234@example.test"))
                .allSatisfy(message -> assertThat(message).doesNotContain(candidate));
    }
}
