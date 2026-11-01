package app.platform.publicapi.internal;

import app.platformapi.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The key a caller chooses for a create or a bulk call, and the hash of the request it stands for (Sprint 30,
 * ADR-0246). Pure. Only hashes ever leave this class: the key and the request are never stored, logged or echoed.
 */
final class IdempotencyKeys {

    /** 8 to 255 printable characters without spaces: letters, digits and the usual separators. */
    private static final Pattern SHAPE = Pattern.compile("^[A-Za-z0-9._:~=+/-]{8,255}$");

    /** One mapper that writes a request the same way every time: keys in order, so equal requests hash equally. */
    private static final JsonMapper CANONICAL = JsonMapper.builder()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();

    private IdempotencyKeys() {
    }

    /**
     * Checks the key.
     *
     * @throws ApiException {@code VALIDATION_ERROR} at {@code Idempotency-Key}; the words never repeat the key
     */
    static String require(String key) {
        if (key == null || !SHAPE.matcher(key).matches()) {
            throw ApiException.validation("Idempotency-Key",
                    "Use 8 to 255 letters, digits or the characters . _ : ~ = + / - without spaces.");
        }
        return key;
    }

    /** The hash of the key, in lower case hexadecimal. */
    static String keyHash(String key) {
        return sha256("key\n" + key);
    }

    /**
     * The hash of a request: what it does, to which object, with which body. The same request always has the same hash,
     * whatever order the caller wrote the keys in; a different request has a different one.
     */
    static String requestHash(String operation, String objectApiName, Object body) {
        return sha256("request\n" + operation + "\n" + objectApiName + "\n" + CANONICAL.writeValueAsString(body));
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every Java platform", e);
        }
    }
}
