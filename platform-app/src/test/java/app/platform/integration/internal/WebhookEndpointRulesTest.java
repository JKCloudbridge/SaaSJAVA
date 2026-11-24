package app.platform.integration.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.integration.internal.EndpointRules.Policy;
import app.platform.integration.internal.EndpointRules.Result;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The address of a webhook is attacker-controlled input to a server that has a network (Sprint 32, ADR-0276): every
 * rule
 * of {@link EndpointRules} applies to it, the whole corpus of hostile spellings of an address is refused for a
 * webhook as
 * it is for a connection, and an address that is changed is a different fingerprint, so it must be verified again.
 */
class WebhookEndpointRulesTest {

    private static final Policy NONE = new Policy(Set.of());
    private static final Path CORPUS = Path.of("src/test/resources/integration/hostile-addresses.txt");

    @Test
    void everyHostileSpellingOfTheCorpusIsRefusedAsAWebhookAddress() throws IOException {
        int checked = 0;
        for (String line : Files.readAllLines(CORPUS, StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String address = line.substring(line.indexOf('|') + 1);
            address = address.startsWith(" ") ? address.substring(1) : address;
            Result result = EndpointRules.parse(address, NONE);

            assertThat(result.ok()).as("accepted: " + line.substring(0, Math.min(line.length(), 40))).isFalse();
            checked++;
        }
        assertThat(checked).isGreaterThan(60);
    }

    @Test
    void theAddressesOfThePlatformItselfAndOfPrivateNetworksAreRefused() {
        List<String> refused = List.of("https://localhost/hooks", "https://127.0.0.1/hooks", "https://[::1]/hooks",
                "https://10.0.0.5/hooks", "https://192.168.1.10/hooks", "https://169.254.169.254/latest/meta-data",
                "https://metadata.internal/hooks", "https://printer.local/hooks", "https://intranet.corp/hooks",
                "https://2130706433/hooks", "https://0x7f.0.0.1/hooks", "http://hooks.example.com/x",
                "https://user:secret@hooks.example.com/x", "https://hooks.example.com/x?token=1",
                "https://hooks.example.com/x#frag", "https://hooks.example.com/../admin",
                "https://hooks.example.com/%2e%2e/admin", "ftp://hooks.example.com/x", "//hooks.example.com/x",
                "hooks.example.com/x", "");

        for (String address : refused) {
            assertThat(EndpointRules.parse(address, NONE).ok()).as(address).isFalse();
        }
    }

    @Test
    void aPublicHttpsAddressWithAPathAndAPortIsAccepted() {
        for (String address : List.of("https://hooks.example.com/in", "https://hooks.example.com:8443/in/v1",
                "https://hooks.example.com")) {
            assertThat(EndpointRules.parse(address, NONE).ok()).as(address).isTrue();
        }
    }

    @Test
    void aRefusalNeverRepeatsWhatWasTyped() {
        String typed = "https://user:topsecret@hooks.example.com/x";

        Result result = EndpointRules.parse(typed, NONE);

        assertThat(result.ok()).isFalse();
        assertThat(result.refusal().words()).doesNotContain("topsecret").doesNotContain("hooks.example.com");
    }

    @Test
    void aChangedAddressIsADifferentFingerprintSoItIsVerifiedAgain() {
        List<String> events = List.of("record.created");
        String before = WebhookSubscriptions.fingerprintOf("https://hooks.example.com/in", events, null);

        assertThat(WebhookSubscriptions.fingerprintOf("https://hooks.example.com/in", events, null)).isEqualTo(before);
        assertThat(WebhookSubscriptions.fingerprintOf("https://hooks.example.com/in2", events, null))
                .isNotEqualTo(before);
        assertThat(WebhookSubscriptions.fingerprintOf("https://hooks.example.com/in", List.of("record.updated"), null))
                .isNotEqualTo(before);
        assertThat(WebhookSubscriptions.fingerprintOf("https://hooks.example.com/in", events, List.of("A__c")))
                .isNotEqualTo(before);
        assertThat(before).matches("[0-9a-f]{64}");
    }

    @Test
    void theFingerprintIsLengthPrefixedSoTwoDefinitionsNeverWriteTheSameBytes() {
        assertThat(WebhookSubscriptions.fingerprintOf("a", List.of("b,c"), null)).isNotEqualTo(
                WebhookSubscriptions.fingerprintOf("a", List.of("b", "c"), null));
        assertThat(WebhookSubscriptions.fingerprintOf("ab", List.of("c"), null)).isNotEqualTo(
                WebhookSubscriptions.fingerprintOf("a", List.of("bc"), null));
    }

    @Test
    void aDevelopmentHostIsTheOnlyWayToAPrivateAddressAndOnlyWhenTheDeploymentNamesIt() {
        Policy development = new Policy(Set.of("127.0.0.1"));

        assertThat(EndpointRules.parse("http://127.0.0.1:8443/in", development).ok()).isTrue();
        assertThat(EndpointRules.parse("http://127.0.0.1:8443/in", NONE).ok()).isFalse();
        assertThat(EndpointRules.parse("http://127.0.0.2:8443/in", development).ok()).isFalse();
    }
}
