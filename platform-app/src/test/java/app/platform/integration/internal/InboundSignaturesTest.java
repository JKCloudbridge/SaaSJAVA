package app.platform.integration.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import app.platform.integration.TestSecrets;
import app.platform.integration.internal.InboundSignatures.Headers;
import app.platform.sharedkernel.TenantId;
import app.platform.sharedkernel.secrets.SecretRef;
import app.platform.sharedkernel.secrets.SecretValue;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The check of the signature of a change that comes in (Sprint 28, ADR-0221). No database: a valid call, a wrong
 * secret,
 * an altered body, another entity, a stale or future time, malformed headers, a missing credential and a store that is
 * down all give the one answer {@code false}; the text that is signed is fixed; and nothing of the secret is printed.
 */
class InboundSignaturesTest {

    private static final Instant NOW = Instant.parse("2030-01-31T09:30:00Z");
    private static final Duration WINDOW = Duration.ofMinutes(5);
    private static final String SECRET = "shared-secret-for-the-test";
    private static final byte[] BODY = "{\"id\":\"x\"}".getBytes(StandardCharsets.UTF_8);

    private final TenantId tenant = new TenantId(UUID.randomUUID());
    private final TestSecrets.Store store = new TestSecrets.Store();
    private final InboundSignatures signatures = new InboundSignatures(provider(store));

    @SuppressWarnings("unchecked")
    private static ObjectProvider<app.platform.sharedkernel.secrets.SecretsStore> provider(
            app.platform.sharedkernel.secrets.SecretsStore store) {
        ObjectProvider<app.platform.sharedkernel.secrets.SecretsStore> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(store);
        return provider;
    }

    private static Headers sign(String secret, String timestamp, String nonce, String entity, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            Headers unsigned = new Headers(timestamp, nonce, null);
            return new Headers(timestamp, nonce,
                    "sha256=" + HexFormat.of().formatHex(mac.doFinal(InboundSignatures.message(unsigned, entity,
                            body))));
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private String seconds(long offset) {
        return Long.toString(NOW.getEpochSecond() + offset);
    }

    private boolean verify(String credential, String entity, byte[] body, Headers headers) {
        return signatures.verify(tenant, credential, entity, body, headers, NOW, WINDOW);
    }

    private void keep() {
        store.put(SecretRef.forTenant(tenant, "signing"), SecretValue.of(SECRET));
    }

    @Test
    void aCallSignedWithTheCredentialsValueForThisEntityAndBodyIsValid() {
        keep();

        assertThat(verify("signing", "item", BODY, sign(SECRET, seconds(0), "nonce-12345678", "item", BODY))).isTrue();
    }

    @Test
    void everyWayOfFailingGivesTheOneAnswer() {
        keep();
        Headers good = sign(SECRET, seconds(0), "nonce-12345678", "item", BODY);

        assertThat(verify("signing", "item", BODY, sign("another-secret-value", seconds(0), "nonce-12345678", "item",
                BODY))).as("wrong secret").isFalse();
        assertThat(verify("signing", "item", "{\"id\":\"y\"}".getBytes(StandardCharsets.UTF_8), good))
                .as("altered body").isFalse();
        assertThat(verify("signing", "other", BODY, good)).as("another entity").isFalse();
        assertThat(verify("signing", "item", BODY, new Headers(good.timestamp(), "nonce-87654321", good.signature())))
                .as("another nonce").isFalse();
        assertThat(verify("signing", "item", BODY, sign(SECRET, seconds(-3600), "nonce-12345678", "item", BODY)))
                .as("stale").isFalse();
        assertThat(verify("signing", "item", BODY, sign(SECRET, seconds(3600), "nonce-12345678", "item", BODY)))
                .as("from the future").isFalse();
        assertThat(verify("missing", "item", BODY, good)).as("no such credential").isFalse();
        assertThat(verify(null, "item", BODY, good)).as("no credential named").isFalse();
    }

    @Test
    void theEdgeOfTheWindowIsInsideAndOneSecondBeyondIsOutside() {
        keep();

        assertThat(verify("signing", "item", BODY, sign(SECRET, seconds(-300), "nonce-12345678", "item", BODY)))
                .isTrue();
        assertThat(verify("signing", "item", BODY, sign(SECRET, seconds(-301), "nonce-12345678", "item", BODY)))
                .isFalse();
        assertThat(verify("signing", "item", BODY, sign(SECRET, seconds(300), "nonce-12345678", "item", BODY)))
                .isTrue();
        assertThat(verify("signing", "item", BODY, sign(SECRET, seconds(301), "nonce-12345678", "item", BODY)))
                .isFalse();
    }

    @Test
    void malformedHeadersAreRefusedWithoutAnError() {
        keep();
        Headers good = sign(SECRET, seconds(0), "nonce-12345678", "item", BODY);

        for (Headers bad : new Headers[] {new Headers(null, good.nonce(), good.signature()),
            new Headers("not-a-number", good.nonce(), good.signature()),
            new Headers(good.timestamp(), null, good.signature()),
            new Headers(good.timestamp(), "short", good.signature()),
            new Headers(good.timestamp(), "has spaces in it", good.signature()),
            new Headers(good.timestamp(), good.nonce(), null),
            new Headers(good.timestamp(), good.nonce(), "sha256=zz"),
            new Headers(good.timestamp(), good.nonce(), good.signature().toUpperCase(java.util.Locale.ROOT)),
            new Headers(good.timestamp(), good.nonce(), "md5=" + "0".repeat(32))}) {
            assertThat(verify("signing", "item", BODY, bad)).isFalse();
        }
    }

    @Test
    void aStoreThatIsDownIsTheSameAnswerAndTheSecretIsNeverPrinted() {
        keep();
        Headers good = sign(SECRET, seconds(0), "nonce-12345678", "item", BODY);
        store.breakDown(true);

        assertThat(verify("signing", "item", BODY, good)).isFalse();
        assertThat(good.toString()).doesNotContain(SECRET).doesNotContain(good.nonce());
        assertThat(signatures.toString()).doesNotContain(SECRET);
    }

    @Test
    void theTextThatIsSignedIsFixedAndIncludesTheEntityAndTheExactBytes() {
        byte[] text = InboundSignatures.message(new Headers("1700000000", "abcdefgh", null), "item", BODY);

        assertThat(new String(text, StandardCharsets.UTF_8))
                .isEqualTo("v1\n1700000000\nabcdefgh\nitem\n{\"id\":\"x\"}");
    }
}
