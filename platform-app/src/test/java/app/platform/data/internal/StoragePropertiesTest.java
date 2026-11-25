package app.platform.data.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * How the platform is known to the object store (ADR-0290): by a key and a secret, or by a role the platform it runs on
 * attaches to the process. The two never mix, and a forgotten secret can never silently become "use whatever role is
 * there".
 */
class StoragePropertiesTest {

    private static StorageProperties properties(String bucket, String accessKey, String secretKey, String credentials) {
        return new StorageProperties("", "us-east-1", bucket, accessKey, secretKey, false, Duration.ofSeconds(60),
                Duration.ofMinutes(3), credentials);
    }

    @Test
    void keysAreTheDefaultAndNeedBoth() {
        assertThat(properties("files", "k", "s", "keys").configured()).isTrue();
        assertThat(properties("files", "k", "s", "keys").usesRole()).isFalse();
    }

    @Test
    void nothingAtAllIsNotConfiguredAndNotAnError() {
        // A developer machine or a test without a store: files are refused with a clean 503.
        assertThat(properties("", "", "", "keys").configured()).isFalse();
    }

    @Test
    void aBucketWithoutBothKeysStopsTheStartInKeysMode() {
        assertThatThrownBy(() -> properties("files", "", "", "keys")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("credentials=role");
        assertThatThrownBy(() -> properties("files", "k", "", "keys")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties("files", "", "s", "keys")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aRoleNeedsOnlyTheBucket() {
        StorageProperties role = properties("files", "", "", "role");

        assertThat(role.usesRole()).isTrue();
        assertThat(role.configured()).isTrue();
        assertThat(properties("", "", "", "role").configured()).as("no bucket, no store").isFalse();
    }

    @Test
    void aRoleWithAKeyIsRefusedInsteadOfPickingOne() {
        assertThatThrownBy(() -> properties("files", "k", "s", "role")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("takes no access key");
        assertThatThrownBy(() -> properties("files", "k", "", "role")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anUnknownModeIsRefused() {
        assertThatThrownBy(() -> properties("files", "k", "s", "instance")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'keys' or 'role'");
    }

    @Test
    void theSettingsNeverPrint() {
        assertThat(properties("files", "KEY-VALUE", "SECRET-VALUE", "keys").toString())
                .doesNotContain("KEY-VALUE", "SECRET-VALUE", "files");
    }
}
