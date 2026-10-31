package app.platform.integration.internal;

import app.platform.sharedkernel.TenantId;
import app.platform.sharedkernel.secrets.SecretRef;
import app.platform.sharedkernel.secrets.SecretValue;
import app.platform.sharedkernel.secrets.SecretsStore;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Checks the signature of a change that comes in from an external system (Sprint 28, ADR-0221). The one place, with the
 * secrets store and the code that builds the header of an outgoing call, that reveals the value of a secret (an
 * architecture test): the value is resolved, used as the key of one HMAC, wiped, and never held, logged or returned.
 *
 * <p><strong>The scheme.</strong> The sender sends three headers: the time in whole seconds since 1970, a nonce of 8 to
 * 64 letters, digits, dashes and underscores that it never repeats, and {@code sha256=} followed by the hex of the
 * HMAC-SHA256, keyed with the credential's value, of the text {@code v1}, a line feed, the time, a line feed, the
 * nonce,
 * a line feed, the entity named in the address, a line feed and then the exact bytes of the body. The entity is inside
 * the signature so a signed body cannot be sent to another entity of the same connection; the nonce is kept (as a hash)
 * for the replay window by {@link ReplayStore}; the time must be within the window either side of now.
 *
 * <p><strong>No oracle.</strong> Every way of failing (a malformed header, a time outside the window, no credential, a
 * wrong signature) is the same answer, and the work is the same: when there is no credential to key it, a fixed key is
 * used and the result thrown away, so an unknown connection takes as long as a known one. The comparison of the two
 * signatures is {@link MessageDigest#isEqual}, which does not stop at the first different byte.
 */
@Component
class InboundSignatures {

    /** The three headers as received. */
    record Headers(String timestamp, String nonce, String signature) {

        @Override
        public String toString() {
            return "Headers[redacted]";
        }
    }

    static final String TIMESTAMP_HEADER = "X-Platform-Timestamp";
    static final String NONCE_HEADER = "X-Platform-Nonce";
    static final String SIGNATURE_HEADER = "X-Platform-Signature";

    private static final Pattern TIMESTAMP = Pattern.compile("[0-9]{1,12}");
    private static final Pattern NONCE = Pattern.compile("[A-Za-z0-9_-]{8,64}");
    private static final Pattern SIGNATURE = Pattern.compile("sha256=[0-9a-f]{64}");
    private static final byte[] DUMMY_KEY = "no credential: a fixed key keeps the work the same".getBytes(
            StandardCharsets.US_ASCII);
    private static final String MAC = "HmacSHA256";

    private final ObjectProvider<SecretsStore> secrets;

    InboundSignatures(ObjectProvider<SecretsStore> secrets) {
        this.secrets = secrets;
    }

    /**
     * Whether the headers sign this entity and body with the credential's value, now.
     *
     * @param credentialName the name of the credential, or null when the connection has none
     */
    boolean verify(TenantId tenant, String credentialName, String entity, byte[] body, Headers headers, Instant now,
            Duration window) {
        boolean shaped = headers.timestamp() != null && TIMESTAMP.matcher(headers.timestamp()).matches()
                && headers.nonce() != null && NONCE.matcher(headers.nonce()).matches()
                && headers.signature() != null && SIGNATURE.matcher(headers.signature()).matches();
        boolean fresh = shaped && withinWindow(Long.parseLong(headers.timestamp()), now, window);
        byte[] given = shaped ? HexFormat.of().parseHex(headers.signature().substring("sha256=".length()))
                : new byte[32];
        byte[] expected = mac(tenant, credentialName, shaped ? headers : new Headers("0", "xxxxxxxx", ""), entity,
                body);
        boolean same = expected != null && MessageDigest.isEqual(expected, given);
        return shaped && fresh && same;
    }

    /** The text that is signed, for the sender and for the check. */
    static byte[] message(Headers headers, String entity, byte[] body) {
        byte[] head = ("v1\n" + headers.timestamp() + "\n" + headers.nonce() + "\n" + entity + "\n")
                .getBytes(StandardCharsets.UTF_8);
        byte[] whole = Arrays.copyOf(head, head.length + body.length);
        System.arraycopy(body, 0, whole, head.length, body.length);
        return whole;
    }

    private static boolean withinWindow(long seconds, Instant now, Duration window) {
        long difference = Math.abs(now.getEpochSecond() - seconds);
        return difference <= window.toSeconds();
    }

    /** The HMAC of the message, or null when the credential cannot be read (the fixed key is still worked with). */
    private byte[] mac(TenantId tenant, String credentialName, Headers headers, String entity, byte[] body) {
        byte[] key = null;
        SecretValue value = null;
        char[] plain = null;
        try {
            Optional<SecretValue> found = credentialName == null ? Optional.empty() : resolve(tenant, credentialName);
            if (found.isPresent()) {
                value = found.get();
                plain = value.reveal();
                ByteBuffer encoded = StandardCharsets.UTF_8.encode(CharBuffer.wrap(plain));
                key = new byte[encoded.remaining()];
                encoded.get(key);
            }
            boolean real = key != null && key.length > 0;
            byte[] used = real ? key : DUMMY_KEY;
            Mac hmac = Mac.getInstance(MAC);
            hmac.init(new SecretKeySpec(used, MAC));
            byte[] result = hmac.doFinal(message(headers, entity, body));
            return real ? result : null;
        } catch (GeneralSecurityException | RuntimeException unavailable) {
            // Deliberately without the exception: a store's message could carry secret material.
            return null;
        } finally {
            if (plain != null) {
                Arrays.fill(plain, '\0');
            }
            if (key != null) {
                Arrays.fill(key, (byte) 0);
            }
            if (value != null) {
                value.destroy();
            }
        }
    }

    private Optional<SecretValue> resolve(TenantId tenant, String credentialName) {
        SecretsStore store = secrets.getIfAvailable();
        if (store == null) {
            return Optional.empty();
        }
        return store.resolve(SecretRef.forTenant(tenant, credentialName));
    }
}
