package app.platform.identity.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Making, hashing and checking the secrets of connected applications (Sprint 31, ADR-0268).
 *
 * <p>A secret is 256 bits from a secure random source, written as {@value #PREFIX} and 43 URL-safe characters: the
 * start is not secret and lets a scanner of leaked text find one. It is shown once, when it is made, and only its
 * SHA-256 hash is stored. A fast hash is the right one here: the secret has full entropy, so there is nothing to guess
 * and nothing for a slow hash to protect, and the token endpoint is open to anyone, so it must never be a cheap way to
 * burn the server's processor time (a password hash would be exactly that). The comparison takes the same time
 * whatever the first difference is.
 *
 * <p>A client identifier is {@code ca_} and 24 letters and digits (about 143 bits): not guessable, and not a secret.
 */
final class ClientSecrets {

    /** The start of every secret. */
    static final String PREFIX = "cs_";

    /** The start of every client identifier. */
    static final String CLIENT_ID_PREFIX = "ca_";

    private static final int MAX_PRESENTED = 256;
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Hash of a fixed text, compared against when the application does not exist, so the time does not tell. */
    private static final String DUMMY = Hashes.hashed("no-such-secret");

    private ClientSecrets() {
    }

    /** A new secret, in the text that is shown once. */
    static String generate() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** A new client identifier. */
    static String newClientId() {
        StringBuilder text = new StringBuilder(CLIENT_ID_PREFIX);
        for (int i = 0; i < 24; i++) {
            text.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return text.toString();
    }

    /** The form in which a secret is stored. */
    static String hash(String secret) {
        return Hashes.hashed(secret);
    }

    /**
     * Whether the presented text is the secret behind the stored hash, compared in constant time. A presented text that
     * is far too long is refused without hashing it.
     */
    static boolean matches(String presented, String storedHash) {
        if (presented == null || presented.length() > MAX_PRESENTED) {
            return false;
        }
        return equalHashes(hash(presented), storedHash);
    }

    /** Spends the time of a check against a secret that does not exist, and always says no. */
    static boolean matchesNothing(String presented) {
        if (presented != null && presented.length() <= MAX_PRESENTED) {
            equalHashes(hash(presented), DUMMY);
        }
        return false;
    }

    private static boolean equalHashes(String left, String right) {
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }
}
