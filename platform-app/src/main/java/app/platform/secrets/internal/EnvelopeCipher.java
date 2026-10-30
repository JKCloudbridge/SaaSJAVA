package app.platform.secrets.internal;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Envelope encryption of one secret version (ADR-0212). A fresh 256-bit data key encrypts the value; the key-encryption
 * key wraps the data key. Both steps are AES-256 in Galois/Counter mode from the JDK, so a changed byte is detected,
 * and both bind the result to the secret's own address as authenticated associated data: a sealed value or a wrapped
 * key copied to another secret, version or organization, or a wrapped key presented as a sealed value, does not open.
 *
 * <p>Nonces are 96 random bits drawn fresh for every operation. A data key is used once (one value), so its nonce can
 * never repeat under it; the key-encryption key wraps one data key per version, far below the volume at which random
 * 96-bit nonces become a risk, and rotating that key bounds it further.
 *
 * <p>Pure: no database, no Spring. Every failure is the same {@link SealException}, which has no message and no cause
 * (the library's text could carry key material).
 */
final class EnvelopeCipher {

    static final String ALGORITHM = "A256GCM-1";
    static final int NONCE_BYTES = 12;
    static final int TAG_BITS = 128;
    static final int DATA_KEY_BYTES = 32;
    /** The size of a wrapped data key: nonce, key, tag. */
    static final int WRAPPED_BYTES = NONCE_BYTES + DATA_KEY_BYTES + TAG_BITS / 8;

    /** Opening or sealing failed. No message, no cause. */
    static final class SealException extends Exception {

        private static final long serialVersionUID = 1L;

        SealException() {
            super(null, null, false, false);
        }
    }

    /**
     * Where a version lives: the associated data both ciphertexts are bound to.
     *
     * @param scope {@code tenant:<id>}
     * @param name the name of the secret
     * @param ordinal the number of the version
     */
    record Binding(String scope, String name, int ordinal) {

        private byte[] aad(String purpose) {
            return ("sv1|" + purpose + "|" + scope + "|" + name + "|" + ordinal).getBytes(StandardCharsets.UTF_8);
        }
    }

    /** The two ciphertexts of a version and the key that wrapped the data key. */
    static final class Sealed {

        private final String keyId;
        private final byte[] wrappedKey;
        private final byte[] sealedValue;

        Sealed(String keyId, byte[] wrappedKey, byte[] sealedValue) {
            this.keyId = keyId;
            this.wrappedKey = wrappedKey.clone();
            this.sealedValue = sealedValue.clone();
        }

        String keyId() {
            return keyId;
        }

        byte[] wrappedKey() {
            return wrappedKey.clone();
        }

        byte[] sealedValue() {
            return sealedValue.clone();
        }

        @Override
        public String toString() {
            return "Sealed[redacted]";
        }
    }

    private final SecureRandom random = new SecureRandom();

    /** Seals the value under a new data key wrapped by {@code kek}. The caller wipes {@code plaintext}. */
    Sealed seal(KeyEncryptionKeys.Key kek, Binding binding, byte[] plaintext) throws SealException {
        byte[] dataKey = new byte[DATA_KEY_BYTES];
        try {
            random.nextBytes(dataKey);
            byte[] sealed = encrypt(new SecretKeySpec(dataKey, "AES"), binding.aad("val"), plaintext);
            byte[] wrapped = encrypt(kek.secret(), binding.aad("key"), dataKey);
            return new Sealed(kek.id(), wrapped, sealed);
        } finally {
            Arrays.fill(dataKey, (byte) 0);
        }
    }

    /** Opens a version. The caller wipes the bytes it gets. */
    byte[] open(KeyEncryptionKeys.Key kek, Binding binding, byte[] wrappedKey, byte[] sealedValue)
            throws SealException {
        byte[] dataKey = decrypt(kek.secret(), binding.aad("key"), wrappedKey);
        try {
            if (dataKey.length != DATA_KEY_BYTES) {
                throw new SealException();
            }
            return decrypt(new SecretKeySpec(dataKey, "AES"), binding.aad("val"), sealedValue);
        } finally {
            Arrays.fill(dataKey, (byte) 0);
        }
    }

    /** Wraps the data key of a version again under another key-encryption key, without touching the value. */
    byte[] rewrap(KeyEncryptionKeys.Key from, KeyEncryptionKeys.Key to, Binding binding, byte[] wrappedKey)
            throws SealException {
        byte[] dataKey = decrypt(from.secret(), binding.aad("key"), wrappedKey);
        try {
            return encrypt(to.secret(), binding.aad("key"), dataKey);
        } finally {
            Arrays.fill(dataKey, (byte) 0);
        }
    }

    private byte[] encrypt(SecretKey key, byte[] aad, byte[] plain) throws SealException {
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad);
            byte[] body = cipher.doFinal(plain);
            byte[] out = new byte[NONCE_BYTES + body.length];
            System.arraycopy(nonce, 0, out, 0, NONCE_BYTES);
            System.arraycopy(body, 0, out, NONCE_BYTES, body.length);
            return out;
        } catch (GeneralSecurityException e) {
            throw new SealException();
        }
    }

    private static byte[] decrypt(SecretKey key, byte[] aad, byte[] sealed) throws SealException {
        if (sealed == null || sealed.length < NONCE_BYTES + TAG_BITS / 8) {
            throw new SealException();
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, sealed, 0, NONCE_BYTES));
            cipher.updateAAD(aad);
            return cipher.doFinal(sealed, NONCE_BYTES, sealed.length - NONCE_BYTES);
        } catch (GeneralSecurityException e) {
            throw new SealException();
        }
    }
}
