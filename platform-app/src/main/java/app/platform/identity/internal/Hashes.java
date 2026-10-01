package app.platform.identity.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/** Small helpers for secrets that are stored or counted only as hashes. */
final class Hashes {

    /** Marks a stored value as a hash; no raw token can contain a colon (tokens are URL-safe base64). */
    static final String HASH_PREFIX = "sha256:";

    private static final SecureRandom RANDOM = new SecureRandom();

    private Hashes() {
    }

    /** SHA-256 of the text as lower-case hexadecimal. */
    static String sha256Hex(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /** The form in which a bearer secret (token, cookie value) is stored: its hash, marked as such. */
    static String stored(String secret) {
        return secret.startsWith(HASH_PREFIX) ? secret : HASH_PREFIX + sha256Hex(secret);
    }

    /** A new random secret of 32 bytes, URL-safe, for cookies. */
    static String randomSecret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
