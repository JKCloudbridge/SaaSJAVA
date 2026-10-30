package app.platform.secrets.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Where the key-encryption keys come from (ADR-0212): the deployment supplies them, a bad or missing key stops the
 * start with a message that says which setting is wrong and never repeats a value or a path.
 */
class KeyEncryptionKeysTest {

    private static String key(int seed) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) seed);
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static SecretsProperties.Kek kek(String id, String value, String file,
            SecretsProperties.Previous... previous) {
        return new SecretsProperties.Kek(id, value, file, List.of(previous));
    }

    @Test
    void theActiveKeyComesFromAnEnvironmentValue() {
        KeyEncryptionKeys keys = new KeyEncryptionKeys(kek("env-key", key(1), null));

        assertThat(keys.active().id()).isEqualTo("env-key");
        assertThat(keys.find("env-key")).isPresent();
        assertThat(keys.find("unknown")).isEmpty();
    }

    @Test
    void theActiveKeyComesFromAMountedFileWithSurroundingWhiteSpace(@TempDir Path folder) throws IOException {
        Path file = folder.resolve("kek.txt");
        Files.writeString(file, "  " + key(2) + "\n");

        KeyEncryptionKeys keys = new KeyEncryptionKeys(kek("file-key", null, file.toString()));

        assertThat(keys.active().id()).isEqualTo("file-key");
    }

    @Test
    void urlSafeBase64AndMissingPaddingAreAccepted() {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) 0xfb);
        String urlSafe = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        assertThat(new KeyEncryptionKeys(kek("k", urlSafe, null)).active().id()).isEqualTo("k");
    }

    @Test
    void previousKeysOnlyOpenWhatTheyWrapped() {
        KeyEncryptionKeys keys = new KeyEncryptionKeys(kek("new", key(1), null,
                new SecretsProperties.Previous("old", key(2), null)));

        assertThat(keys.active().id()).isEqualTo("new");
        assertThat(keys.find("old")).isPresent();
    }

    @Test
    void withoutAKeyTheStartIsRefusedAndSaysWhichSettingIsWrong() {
        assertThatThrownBy(() -> new KeyEncryptionKeys(kek(null, null, null)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("platform.secrets.kek.active-id");
        assertThatThrownBy(() -> new KeyEncryptionKeys(kek("k", null, null)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("platform.secrets.kek.active");
        assertThatThrownBy(() -> new KeyEncryptionKeys(kek("k", "", "  ")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aKeyGivenTwiceIsRefused() {
        assertThatThrownBy(() -> new KeyEncryptionKeys(kek("k", key(1), "/some/file")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("either");
    }

    @Test
    void aKeyOfTheWrongSizeOrShapeIsRefusedWithoutRepeatingIt() {
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);
        for (String bad : new String[] {shortKey, "not base64 at all !!", key(1) + key(1)}) {
            assertThatThrownBy(() -> new KeyEncryptionKeys(kek("k", bad, null)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("32 random bytes")
                    .satisfies(failure -> assertThat(failure.getMessage()).doesNotContain(bad));
        }
    }

    @Test
    void aFileThatCannotBeReadIsRefusedWithoutRepeatingThePath(@TempDir Path folder) {
        String missing = folder.resolve("secret-folder-name").resolve("kek.txt").toString();

        assertThatThrownBy(() -> new KeyEncryptionKeys(kek("k", null, missing)))
                .isInstanceOf(IllegalStateException.class)
                .satisfies(failure -> assertThat(failure.getMessage()).doesNotContain("secret-folder-name"))
                .hasNoCause();
    }

    @Test
    void keyIdentifiersAreShortAndDistinct() {
        assertThatThrownBy(() -> new KeyEncryptionKeys(kek("has space", key(1), null)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new KeyEncryptionKeys(kek("same", key(1), null,
                new SecretsProperties.Previous("same", key(2), null)))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("different");
    }

    @Test
    void nothingThatHoldsAKeyPrintsIt() {
        SecretsProperties.Kek settings = kek("k", key(7), null, new SecretsProperties.Previous("o", key(8), null));
        KeyEncryptionKeys keys = new KeyEncryptionKeys(settings);

        assertThat(settings.toString()).doesNotContain(key(7)).isEqualTo("Kek[redacted]");
        assertThat(settings.previous().get(0).toString()).doesNotContain(key(8));
        assertThat(keys.active().toString()).isEqualTo("Key[redacted]");
    }
}
