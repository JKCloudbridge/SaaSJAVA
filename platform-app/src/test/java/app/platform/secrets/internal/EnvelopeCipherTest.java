package app.platform.secrets.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.platform.secrets.internal.EnvelopeCipher.Binding;
import app.platform.secrets.internal.EnvelopeCipher.SealException;
import app.platform.secrets.internal.EnvelopeCipher.Sealed;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The envelope cipher (ADR-0212): a value comes back only under the key and the address it was sealed for, and any
 * change of a byte, of the key or of the address is refused, with no message that could carry key material.
 */
class EnvelopeCipherTest {

    private static final String TENANT_A = "tenant:11111111-1111-4111-8111-111111111111";
    private static final String TENANT_B = "tenant:22222222-2222-4222-8222-222222222222";
    private final EnvelopeCipher cipher = new EnvelopeCipher();

    private static KeyEncryptionKeys.Key key(String id, int seed) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) seed);
        String encoded = Base64.getEncoder().encodeToString(bytes);
        return new KeyEncryptionKeys(new SecretsProperties.Kek(id, encoded, null, java.util.List.of())).active();
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void aValueComesBackUnderTheKeyAndTheAddressItWasSealedFor() throws SealException {
        KeyEncryptionKeys.Key kek = key("k1", 1);
        Binding binding = new Binding(TENANT_A, "erp-key", 1);

        Sealed sealed = cipher.seal(kek, binding, bytes("canary-ü-日本"));

        assertThat(cipher.open(kek, binding, sealed.wrappedKey(), sealed.sealedValue()))
                .isEqualTo(bytes("canary-ü-日本"));
        assertThat(sealed.keyId()).isEqualTo("k1");
        assertThat(sealed.wrappedKey()).hasSize(EnvelopeCipher.WRAPPED_BYTES);
    }

    @Test
    void theCiphertextHoldsNoPlaintext() throws SealException {
        KeyEncryptionKeys.Key kek = key("k1", 1);
        Sealed sealed = cipher.seal(kek, new Binding(TENANT_A, "n", 1), bytes("canary-value-in-plain"));

        assertThat(new String(sealed.sealedValue(), StandardCharsets.ISO_8859_1)).doesNotContain("canary");
        assertThat(new String(sealed.wrappedKey(), StandardCharsets.ISO_8859_1)).doesNotContain("canary");
        assertThat(sealed.toString()).doesNotContain("canary").isEqualTo("Sealed[redacted]");
    }

    @Test
    void theWrongKeyDoesNotOpenIt() throws SealException {
        Binding binding = new Binding(TENANT_A, "erp-key", 1);
        Sealed sealed = cipher.seal(key("k1", 1), binding, bytes("value"));

        assertThatThrownBy(() -> cipher.open(key("k1", 2), binding, sealed.wrappedKey(), sealed.sealedValue()))
                .isInstanceOf(SealException.class).hasMessage(null).hasNoCause();
    }

    @Test
    void aChangedByteInEitherCiphertextIsRefused() throws SealException {
        KeyEncryptionKeys.Key kek = key("k1", 1);
        Binding binding = new Binding(TENANT_A, "erp-key", 1);
        Sealed sealed = cipher.seal(kek, binding, bytes("value"));
        for (int at = 0; at < sealed.sealedValue().length; at++) {
            byte[] changed = sealed.sealedValue();
            changed[at] ^= 0x01;
            assertThatThrownBy(() -> cipher.open(kek, binding, sealed.wrappedKey(), changed))
                    .isInstanceOf(SealException.class);
        }
        for (int at = 0; at < sealed.wrappedKey().length; at++) {
            byte[] changed = sealed.wrappedKey();
            changed[at] ^= 0x01;
            assertThatThrownBy(() -> cipher.open(kek, binding, changed, sealed.sealedValue()))
                    .isInstanceOf(SealException.class);
        }
    }

    @Test
    void aCiphertextMovedToAnotherSecretVersionOrOrganizationDoesNotOpen() throws SealException {
        KeyEncryptionKeys.Key kek = key("k1", 1);
        Sealed sealed = cipher.seal(kek, new Binding(TENANT_A, "erp-key", 1), bytes("value"));

        for (Binding elsewhere : new Binding[] {new Binding(TENANT_A, "other-key", 1),
                new Binding(TENANT_A, "erp-key", 2), new Binding(TENANT_B, "erp-key", 1)}) {
            assertThatThrownBy(() -> cipher.open(kek, elsewhere, sealed.wrappedKey(), sealed.sealedValue()))
                    .isInstanceOf(SealException.class);
        }
    }

    @Test
    void aWrappedKeyCannotStandInForASealedValue() throws SealException {
        KeyEncryptionKeys.Key kek = key("k1", 1);
        Binding binding = new Binding(TENANT_A, "erp-key", 1);
        Sealed sealed = cipher.seal(kek, binding, bytes("value"));

        assertThatThrownBy(() -> cipher.open(kek, binding, sealed.sealedValue(), sealed.sealedValue()))
                .isInstanceOf(SealException.class);
        assertThatThrownBy(() -> cipher.open(kek, binding, sealed.wrappedKey(), sealed.wrappedKey()))
                .isInstanceOf(SealException.class);
    }

    @Test
    void truncatedInputIsRefusedWithoutAnyOtherFailure() {
        KeyEncryptionKeys.Key kek = key("k1", 1);
        Binding binding = new Binding(TENANT_A, "erp-key", 1);

        assertThatThrownBy(() -> cipher.open(kek, binding, new byte[3], new byte[3])).isInstanceOf(SealException.class);
        assertThatThrownBy(() -> cipher.open(kek, binding, new byte[0], new byte[0]))
                .isInstanceOf(SealException.class);
    }

    @Test
    void everySealingUsesANewNonceAndANewDataKey() throws SealException {
        KeyEncryptionKeys.Key kek = key("k1", 1);
        Binding binding = new Binding(TENANT_A, "erp-key", 1);
        Set<String> nonces = new HashSet<>();
        Set<String> wrapped = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            Sealed sealed = cipher.seal(kek, binding, bytes("same value"));
            nonces.add(Base64.getEncoder().encodeToString(Arrays.copyOf(sealed.sealedValue(), 12)));
            wrapped.add(Base64.getEncoder().encodeToString(sealed.wrappedKey()));
        }

        assertThat(nonces).hasSize(200);
        assertThat(wrapped).hasSize(200);
    }

    @Test
    void aDataKeyCanBeWrappedAgainUnderAnotherKeyWithoutTouchingTheValue() throws SealException {
        KeyEncryptionKeys.Key oldKey = key("old", 1);
        KeyEncryptionKeys.Key newKey = key("new", 2);
        Binding binding = new Binding(TENANT_A, "erp-key", 3);
        Sealed sealed = cipher.seal(oldKey, binding, bytes("value"));

        byte[] again = cipher.rewrap(oldKey, newKey, binding, sealed.wrappedKey());

        assertThat(cipher.open(newKey, binding, again, sealed.sealedValue())).isEqualTo(bytes("value"));
        assertThatThrownBy(() -> cipher.open(oldKey, binding, again, sealed.sealedValue()))
                .isInstanceOf(SealException.class);
        assertThatThrownBy(() -> cipher.rewrap(newKey, oldKey, new Binding(TENANT_B, "erp-key", 3), again))
                .isInstanceOf(SealException.class);
    }

    @Test
    void theAccessorsHandOutCopies() throws SealException {
        KeyEncryptionKeys.Key kek = key("k1", 1);
        Binding binding = new Binding(TENANT_A, "erp-key", 1);
        Sealed sealed = cipher.seal(kek, binding, bytes("value"));

        sealed.sealedValue()[0] ^= 0x7f;
        sealed.wrappedKey()[0] ^= 0x7f;

        assertThat(cipher.open(kek, binding, sealed.wrappedKey(), sealed.sealedValue())).isEqualTo(bytes("value"));
    }
}
