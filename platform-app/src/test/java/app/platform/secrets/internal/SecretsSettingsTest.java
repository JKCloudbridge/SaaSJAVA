package app.platform.secrets.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.platform.sharedkernel.secrets.SecretStoreException;
import app.platform.sharedkernel.secrets.SecretStoreException.Reason;
import java.util.Arrays;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** The limits of the store (ADR-0212) and the one shape of its failures (ADR-0214). */
class SecretsSettingsTest {

    @Test
    void theDefaultsAreInsideTheirOwnBounds() {
        SecretsProperties.Limits limits = new SecretsProperties.Limits(4096, 200, 3);

        assertThat(limits.maxValueChars()).isEqualTo(4096);
        assertThat(limits.retainedVersions()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void aLimitOutOfRangeStopsTheStart() {
        assertThatThrownBy(() -> new SecretsProperties.Limits(8, 200, 3)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SecretsProperties.Limits(5000, 200, 3))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SecretsProperties.Limits(4096, 0, 3)).isInstanceOf(IllegalArgumentException.class);
        // Fewer than two versions would leave no way back.
        assertThatThrownBy(() -> new SecretsProperties.Limits(4096, 200, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theLongestValueFitsTheColumnThatHoldsIt() {
        // 4096 characters of at most three bytes each, a 12-byte nonce and a 16-byte tag; the column allows 20000.
        assertThat(4096 * 3 + 12 + 16).isLessThan(20000);
    }

    @Test
    void everyReasonHasAStableKeyAndTheMessageIsOnlyTheKey() {
        Pattern shape = Pattern.compile("[a-z_]{1,40}");
        for (Reason reason : Reason.values()) {
            SecretStoreException failure = new SecretStoreException(reason);

            assertThat(reason.key()).matches(shape);
            assertThat(failure.getMessage()).isEqualTo(reason.key());
            assertThat(failure.getCause()).isNull();
            assertThat(failure.reason()).isEqualTo(reason);
            assertThat(failure.getStackTrace()).isEmpty();
        }
        assertThat(Arrays.stream(Reason.values()).map(Reason::key)).doesNotHaveDuplicates();
    }
}
