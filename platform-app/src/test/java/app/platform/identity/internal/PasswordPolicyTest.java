package app.platform.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Story S3-SEC-03, revised by the product owner on 2026-10-10: minimum and maximum length, mandatory upper case,
 * lower case, digit and symbol, a list of common passwords, no address.
 */
class PasswordPolicyTest {

    private final PasswordPolicy policy = new PasswordPolicy(
            new IdentityProperties.Password(9, 64, 1024, 2, 1, 4, Duration.ofSeconds(2)),
            new BundledCommonPasswords());

    @Test
    void aPasswordWithEveryRequiredCharacterKindIsAccepted() {
        assertThat(policy.violations("Purple7!Elephant".toCharArray(), "user-a@example.test")).isEmpty();
    }

    @Test
    void aPasswordMissingASymbolIsRefused() {
        assertThat(policy.violations("Purple7Elephant".toCharArray(), null))
                .containsExactly("Must include at least one symbol (for example ! @ # $ % & *).");
    }

    @Test
    void aPasswordMissingADigitIsRefused() {
        assertThat(policy.violations("Purple!Elephant".toCharArray(), null))
                .containsExactly("Must include at least one number.");
    }

    @Test
    void aPasswordMissingUpperCaseIsRefused() {
        assertThat(policy.violations("purple7!elephant".toCharArray(), null))
                .containsExactly("Must include at least one upper case letter.");
    }

    @Test
    void aPasswordMissingLowerCaseIsRefused() {
        assertThat(policy.violations("PURPLE7!ELEPHANT".toCharArray(), null))
                .containsExactly("Must include at least one lower case letter.");
    }

    @Test
    void aShortPasswordIsRefusedWithAMessageThatNamesTheMinimum() {
        assertThat(policy.violations("Sh0rt!".toCharArray(), null)).contains("Must have at least 9 characters.");
    }

    @Test
    void anEnormousPasswordIsRefusedBeforeAnyHashingHappens() {
        assertThat(policy.violations("x".repeat(65).toCharArray(), null))
                .containsExactly("Must have at most 64 characters.");
    }

    @Test
    void theLengthCountsCharactersNotBytes() {
        // Nine characters, each of several bytes, plus the required character kinds.
        char[] candidate = "üñîçødéüÑ7!".toCharArray();

        assertThat(policy.violations(candidate, null)).isEmpty();
    }

    @Test
    void aCommonPasswordIsRefusedEvenWhenLongEnoughAfterAddingDigits() {
        assertThat(policy.violations("Password123456!".toCharArray(), null))
                .anyMatch(message -> message.contains("common"));
    }

    @Test
    void aPasswordBuiltFromTheAddressIsRefused() {
        assertThat(policy.violations("My-User-a-Secret-1!".toCharArray(), "user-a@example.test"))
                .contains("Must not contain your email address.");
        assertThat(policy.violations("Ab-Secret-12345!".toCharArray(), "ab@example.test")).isEmpty();
    }

    @Test
    void noMessageRepeatsThePassword() {
        String candidate = "Password1234!";

        assertThat(policy.violations(candidate.toCharArray(), "password1234@example.test"))
                .allSatisfy(message -> assertThat(message).doesNotContain(candidate));
    }
}
