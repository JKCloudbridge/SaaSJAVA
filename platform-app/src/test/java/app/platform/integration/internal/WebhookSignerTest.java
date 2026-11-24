package app.platform.integration.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.integration.TestSecrets;
import app.platform.sharedkernel.TenantId;
import app.platform.sharedkernel.secrets.SecretRef;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.AlphaChars;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;
import net.jqwik.api.constraints.StringLength;
import org.junit.jupiter.api.Test;

/**
 * The signature of a webhook delivery (Sprint 32, ADR-0277): the exact string that is signed, a changed byte, a stale
 * time and a wrong key all fail, two signatures during a rotation are each enough, and a secret is sealed under a name
 * made of identifiers and never printed.
 */
class WebhookSignerTest {

    private static final byte[] BODY = "{\"payloadVersion\":1,\"id\":\"x\"}".getBytes(StandardCharsets.UTF_8);
    private static final byte[] KEY = "whsec_first".getBytes(StandardCharsets.UTF_8);
    private static final byte[] OTHER = "whsec_second".getBytes(StandardCharsets.UTF_8);
    private static final long NOW = 1_800_000_000L;

    @Test
    void whatIsSignedIsTheTimeAFullStopAndTheExactBodyBytes() {
        byte[] signed = WebhookSignatures.signedBytes(NOW, BODY);

        assertThat(new String(signed, StandardCharsets.UTF_8)).isEqualTo(NOW + "." + new String(BODY,
                StandardCharsets.UTF_8));
    }

    @Test
    void theSignatureIsHmacSha256OfThatStringCheckedAgainstAnIndependentComputation() throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(KEY, "HmacSHA256"));
        String expected = HexFormat.of().formatHex(mac.doFinal((NOW + "." + new String(BODY,
                StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8)));

        assertThat(WebhookSignatures.sign(KEY, NOW, BODY)).isEqualTo(expected).hasSize(64);
    }

    @Test
    void aReceiverThatRunsTheSameStepsAcceptsTheDelivery() {
        String header = WebhookSignatures.header(List.of(WebhookSignatures.sign(KEY, NOW, BODY)));

        assertThat(WebhookSignatures.verify(header, List.of(KEY), NOW, BODY, NOW + 30, 300)).isTrue();
    }

    @Test
    void aChangedByteOfTheBodyFails() {
        String header = WebhookSignatures.header(List.of(WebhookSignatures.sign(KEY, NOW, BODY)));
        byte[] changed = BODY.clone();
        changed[3] ^= 1;

        assertThat(WebhookSignatures.verify(header, List.of(KEY), NOW, changed, NOW, 300)).isFalse();
    }

    @Test
    void aChangedTimeFailsBecauseTheTimeIsSigned() {
        String header = WebhookSignatures.header(List.of(WebhookSignatures.sign(KEY, NOW, BODY)));

        assertThat(WebhookSignatures.verify(header, List.of(KEY), NOW + 1, BODY, NOW + 1, 300)).isFalse();
    }

    @Test
    void aRecordedDeliverySentAgainLaterIsRefusedByTheWindowOfTime() {
        String header = WebhookSignatures.header(List.of(WebhookSignatures.sign(KEY, NOW, BODY)));

        assertThat(WebhookSignatures.verify(header, List.of(KEY), NOW, BODY, NOW + 301, 300)).isFalse();
        assertThat(WebhookSignatures.verify(header, List.of(KEY), NOW, BODY, NOW - 301, 300)).isFalse();
        assertThat(WebhookSignatures.verify(header, List.of(KEY), NOW, BODY, NOW + 300, 300)).isTrue();
    }

    @Test
    void aWrongKeyFailsAndAMalformedHeaderIsSkippedNotAnError() {
        String header = WebhookSignatures.header(List.of(WebhookSignatures.sign(KEY, NOW, BODY)));

        assertThat(WebhookSignatures.verify(header, List.of(OTHER), NOW, BODY, NOW, 300)).isFalse();
        assertThat(WebhookSignatures.verify("garbage v2=00 v1=", List.of(KEY), NOW, BODY, NOW, 300)).isFalse();
        assertThat(WebhookSignatures.verify(null, List.of(KEY), NOW, BODY, NOW, 300)).isFalse();
        assertThat(WebhookSignatures.verify("", List.of(KEY), NOW, BODY, NOW, 300)).isFalse();
    }

    @Test
    void duringARotationTwoSignaturesAreSentAndEitherOneIsEnough() {
        String header = WebhookSignatures.header(List.of(WebhookSignatures.sign(KEY, NOW, BODY),
                WebhookSignatures.sign(OTHER, NOW, BODY)));

        assertThat(header.split(" ")).hasSize(2).allMatch(part -> part.startsWith("v1="));
        assertThat(WebhookSignatures.verify(header, List.of(KEY), NOW, BODY, NOW, 300)).isTrue();
        assertThat(WebhookSignatures.verify(header, List.of(OTHER), NOW, BODY, NOW, 300)).isTrue();
        assertThat(WebhookSignatures.verify(header, List.of("whsec_third".getBytes(StandardCharsets.UTF_8)), NOW,
                BODY, NOW, 300)).isFalse();
    }

    @Property
    void anySignatureVerifiesWithItsOwnKeyAndFailsWhenAnyInputChanges(@ForAll @AlphaChars @StringLength(min = 1,
            max = 40) String key, @ForAll @AlphaChars @StringLength(max = 60) String body,
            @ForAll @LongRange(min = 1, max = 4_000_000_000L) long time,
            @ForAll @IntRange(min = 1, max = 5) int change) {
        byte[] k = key.getBytes(StandardCharsets.UTF_8);
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        String signature = WebhookSignatures.sign(k, time, b);
        String header = WebhookSignatures.header(List.of(signature));

        assertThat(WebhookSignatures.verify(header, List.of(k), time, b, time, 300)).isTrue();
        assertThat(WebhookSignatures.verify(header, List.of(k), time + change, b, time + change, 300)).isFalse();
        assertThat(WebhookSignatures.verify(header, List.of((key + "x").getBytes(StandardCharsets.UTF_8)), time, b,
                time, 300)).isFalse();
        assertThat(WebhookSignatures.verify(header, List.of(k), time, (body + "x").getBytes(StandardCharsets.UTF_8),
                time, 300)).isFalse();
    }

    @Test
    void theComparisonOfSignaturesIsTheOneThatTakesTheSameTimeWhateverTheContent() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/app/platform/integration/internal/WebhookSignatures.java"));

        assertThat(source).contains("MessageDigest.isEqual");
        // A signature is never compared with String.equals, which stops at the first different character.
        assertThat(source).doesNotContainPattern("(?i)(signature|expected|given)\\w*\\.equals\\(");
    }

    @Test
    void aSecretIsSealedUnderANameMadeOfIdentifiersAndSignsWithTheNumbersAskedFor() {
        TestSecrets.Store store = new TestSecrets.Store();
        WebhookSigner signer = new WebhookSigner(store);
        TenantId tenant = new TenantId(UUID.randomUUID());
        UUID subscription = UUID.randomUUID();
        String first = signer.generate();
        String second = signer.generate();

        signer.seal(tenant, subscription, 1, first);
        signer.seal(tenant, subscription, 2, second);
        WebhookSigner.Signed both = signer.sign(tenant, subscription, List.of(2, 1), NOW, BODY);
        WebhookSigner.Signed onlyNew = signer.sign(tenant, subscription, List.of(2), NOW, BODY);

        assertThat(WebhookSigner.nameOf(subscription, 1)).matches("wh-[0-9a-f]{32}-1");
        assertThat(first).startsWith("whsec_").hasSizeGreaterThan(40).isNotEqualTo(second);
        assertThat(both.failure()).isNull();
        assertThat(both.header().split(" ")).hasSize(2);
        assertThat(onlyNew.header().split(" ")).hasSize(1);
        assertThat(WebhookSignatures.verify(both.header(), List.of(first.getBytes(StandardCharsets.UTF_8)), NOW, BODY,
                NOW, 300)).isTrue();
        assertThat(WebhookSignatures.verify(onlyNew.header(), List.of(first.getBytes(StandardCharsets.UTF_8)), NOW,
                BODY, NOW, 300)).as("the old secret no longer signs when it is not asked for").isFalse();
        assertThat(store.describe(SecretRef.forTenant(tenant, WebhookSigner.nameOf(subscription, 2)))).isPresent();
    }

    @Test
    void aSecretThatWasRemovedStopsSigningAndNothingIsSentUnsigned() {
        TestSecrets.Store store = new TestSecrets.Store();
        WebhookSigner signer = new WebhookSigner(store);
        TenantId tenant = new TenantId(UUID.randomUUID());
        UUID subscription = UUID.randomUUID();
        signer.seal(tenant, subscription, 1, signer.generate());

        signer.remove(tenant, subscription, 1);
        WebhookSigner.Signed after = signer.sign(tenant, subscription, List.of(1), NOW, BODY);

        assertThat(after.header()).isNull();
        assertThat(after.failure()).isEqualTo(TestReason.CREDENTIAL_MISSING);
    }

    @Test
    void aStoreThatIsDownIsAReasonAndNothingMore() {
        TestSecrets.Store store = new TestSecrets.Store();
        WebhookSigner signer = new WebhookSigner(store);
        TenantId tenant = new TenantId(UUID.randomUUID());
        UUID subscription = UUID.randomUUID();
        signer.seal(tenant, subscription, 1, signer.generate());
        store.breakDown(true);

        WebhookSigner.Signed down = signer.sign(tenant, subscription, List.of(1), NOW, BODY);

        assertThat(down.failure()).isEqualTo(TestReason.CREDENTIAL_STORE_UNAVAILABLE);
        assertThat(down.header()).isNull();
    }

    @Test
    void whatIsPrintedNeverHoldsASecretAnAddressOrABody() {
        UUID delivery = UUID.randomUUID();
        WebhookCaller.Request request = new WebhookCaller.Request(new TenantId(UUID.randomUUID()), UUID.randomUUID(),
                List.of(1), new EndpointRules.Endpoint(true, "hooks.example.com", 443, "/secret-path", false),
                "record.updated", delivery, 1, BODY);

        assertThat(request.toString()).isEqualTo("Request[" + delivery + "]")
                .doesNotContain("hooks.example.com").doesNotContain("secret-path").doesNotContain("payloadVersion");
    }
}
