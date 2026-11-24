package app.platform.integration.internal;

import app.platform.sharedkernel.TenantId;
import app.platform.sharedkernel.secrets.SecretRef;
import app.platform.sharedkernel.secrets.SecretValue;
import app.platform.sharedkernel.secrets.SecretsStore;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The signing secrets of webhook subscriptions and the signing itself (Sprint 32, ADR-0277). A signature needs the key,
 * so the secret cannot be a hash like the secret of a connected application: it is sealed in the secrets store under
 * the organization's own scope, with a name made from the subscription's identifier and the number of the secret
 * (never a typed word), and read back only here, at the moment of signing.
 *
 * <p><strong>The one class, with the tester, that reveals a signing secret.</strong> The secret is generated here,
 * shown
 * once by the service that asked for it and never again, kept nowhere else, wiped from memory after use, and never
 * logged, audited or put in an error: a failure of the store is only a stable reason. A secret that was lost is
 * replaced,
 * not read back; a secret that leaked is removed, and then no delivery carries a signature made with it.
 */
@Component
class WebhookSigner {

    /** The prefix that tells a person what a copied value is; it carries no meaning for the signature. */
    private static final String PREFIX = "whsec_";
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * What signing gave.
     *
     * @param header the value of the {@code Webhook-Signature} header, or null when there is none
     * @param failure the reason there is none, or null
     */
    record Signed(String header, TestReason failure) {
    }

    private final SecretsStore secrets;

    WebhookSigner(SecretsStore secrets) {
        this.secrets = secrets;
    }

    /** A new secret: 32 random bytes, written so that it can be copied. */
    String generate() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String value = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Arrays.fill(bytes, (byte) 0);
        return value;
    }

    /** The name of the secret of one number of one subscription: lower case letters, digits and dashes. */
    static String nameOf(UUID subscription, int number) {
        return "wh-" + subscription.toString().replace("-", "") + "-" + number;
    }

    /** Seals the secret under the organization's scope. */
    void seal(TenantId tenant, UUID subscription, int number, String secret) {
        SecretValue value = SecretValue.of(secret);
        try {
            secrets.put(SecretRef.forTenant(tenant, nameOf(subscription, number)), value);
        } finally {
            value.destroy();
        }
    }

    /** Removes the secret of one number; a secret that is not there is not an error. */
    void remove(TenantId tenant, UUID subscription, int number) {
        secrets.delete(SecretRef.forTenant(tenant, nameOf(subscription, number)));
    }

    /**
     * Signs the body with every secret in {@code numbers} (the current one, and the previous one while it overlaps).
     * A secret that cannot be found or opened is skipped; when none can be used the answer is the reason, and the
     * delivery is not sent unsigned.
     */
    Signed sign(TenantId tenant, UUID subscription, List<Integer> numbers, long timestampSeconds, byte[] body) {
        List<String> signatures = new ArrayList<>();
        try {
            for (int number : numbers) {
                Optional<SecretValue> found = secrets.resolve(SecretRef.forTenant(tenant,
                        nameOf(subscription, number)));
                if (found.isEmpty()) {
                    continue;
                }
                SecretValue value = found.get();
                byte[] key = keyOf(value);
                try {
                    signatures.add(WebhookSignatures.sign(key, timestampSeconds, body));
                } finally {
                    Arrays.fill(key, (byte) 0);
                    value.destroy();
                }
            }
        } catch (RuntimeException unavailable) {
            // The store failed: a reason and nothing of what it said (its exceptions carry none by contract).
            return new Signed(null, TestReason.CREDENTIAL_STORE_UNAVAILABLE);
        }
        if (signatures.isEmpty()) {
            return new Signed(null, TestReason.CREDENTIAL_MISSING);
        }
        return new Signed(WebhookSignatures.header(signatures), null);
    }

    /** The key bytes of a secret, encoded without making a string that would stay in memory. */
    private static byte[] keyOf(SecretValue value) {
        char[] chars = value.reveal();
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.encode(CharBuffer.wrap(chars));
            byte[] key = new byte[encoded.remaining()];
            encoded.get(key);
            if (encoded.hasArray()) {
                Arrays.fill(encoded.array(), (byte) 0);
            }
            return key;
        } finally {
            Arrays.fill(chars, '\0');
        }
    }
}
