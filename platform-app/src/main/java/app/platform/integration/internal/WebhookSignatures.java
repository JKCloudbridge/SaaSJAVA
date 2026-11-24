package app.platform.integration.internal;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The signature of a webhook delivery as pure functions (Sprint 32, ADR-0277): HMAC-SHA256, with the secret of the
 * subscription as the key, over the string {@code <timestamp>.<exact body bytes>}. The time is the number of whole
 * seconds since 1970 that is also sent in the {@code Webhook-Timestamp} header, so a receiver that checks it against
 * its
 * own clock refuses a recorded delivery that is sent again later.
 *
 * <p>The header {@code Webhook-Signature} holds one {@code v1=<hex>} for every secret that signs: two while a replaced
 * secret still overlaps, so a receiver that has learned only one of them accepts the delivery. The receiving side of
 * the same idea is {@code InboundSignatures}, which takes the care that is taken here in the other direction: the exact
 * bytes, a window of time and a comparison that takes the same time whether it fails early or late. The verification
 * here exists for the tests and for the guide, which shows a receiver the same steps.
 */
final class WebhookSignatures {

    /** The name of the scheme in the header; a change of the string that is signed is a new name. */
    static final String SCHEME = "v1";

    private static final String ALGORITHM = "HmacSHA256";

    private WebhookSignatures() {
    }

    /** The bytes that are signed: the time, a full stop and the body exactly as sent. */
    static byte[] signedBytes(long timestampSeconds, byte[] body) {
        byte[] prefix = (timestampSeconds + ".").getBytes(StandardCharsets.US_ASCII);
        byte[] all = new byte[prefix.length + body.length];
        System.arraycopy(prefix, 0, all, 0, prefix.length);
        System.arraycopy(body, 0, all, prefix.length, body.length);
        return all;
    }

    /** The signature, in lower case hexadecimal, of the body at the time, with the key. */
    static String sign(byte[] key, long timestampSeconds, byte[] body) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(key, ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(signedBytes(timestampSeconds, body)));
        } catch (NoSuchAlgorithmException | InvalidKeyException unavailable) {
            // HmacSHA256 is part of every Java runtime, and a key of any length is a valid key for it.
            throw new IllegalStateException("The signature algorithm is not available");
        }
    }

    /** The value of the {@code Webhook-Signature} header for the signatures, one {@code v1=<hex>} each. */
    static String header(List<String> signatures) {
        List<String> parts = new ArrayList<>();
        for (String signature : signatures) {
            parts.add(SCHEME + "=" + signature);
        }
        return String.join(" ", parts);
    }

    /**
     * What a receiver does: true when the time is inside the tolerance of {@code nowSeconds} and any signature of the
     * header is the signature of this body made with any of the keys. Every comparison is of the same length and takes
     * the same time, and a malformed part of the header is skipped, never an error.
     */
    static boolean verify(String header, List<byte[]> keys, long timestampSeconds, byte[] body, long nowSeconds,
            long toleranceSeconds) {
        if (header == null || Math.abs(nowSeconds - timestampSeconds) > toleranceSeconds) {
            return false;
        }
        boolean matched = false;
        for (byte[] key : keys) {
            byte[] expected = sign(key, timestampSeconds, body).getBytes(StandardCharsets.US_ASCII);
            for (String part : header.split(" ")) {
                if (!part.startsWith(SCHEME + "=")) {
                    continue;
                }
                byte[] given = part.substring(SCHEME.length() + 1).getBytes(StandardCharsets.US_ASCII);
                // No early exit: the loop takes the same path whether or not an earlier part matched.
                matched |= MessageDigest.isEqual(expected, given);
            }
        }
        return matched;
    }
}
