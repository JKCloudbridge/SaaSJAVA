package app.platform.integration.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.integration.internal.EndpointRules.Endpoint;
import app.platform.integration.internal.EndpointRules.Policy;
import app.platform.integration.internal.EndpointRules.Refusal;
import app.platform.integration.internal.EndpointRules.Result;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The rules for the address of an external system (ADR-0208) against a corpus of hostile spellings. The corpus is a
 * file so that a new way round the rules is one line, and the test fails the build when a line is accepted or when a
 * refusal repeats what was typed.
 */
class EndpointRulesTest {

    private static final Policy NONE = new Policy(Set.of());
    private static final Path CORPUS = Path.of("src/test/resources/integration/hostile-addresses.txt");

    private record Line(String reason, String address) {
    }

    private static List<Line> corpus() throws IOException {
        List<Line> lines = new ArrayList<>();
        for (String line : Files.readAllLines(CORPUS, StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            int bar = line.indexOf('|');
            String address = line.substring(bar + 1);
            lines.add(new Line(line.substring(0, bar).strip(), address.startsWith(" ") ? address.substring(1)
                    : address));
        }
        return lines;
    }

    @Test
    void everyHostileSpellingInTheCorpusIsRefusedWithoutRepeatingIt() throws IOException {
        List<Line> lines = corpus();
        assertThat(lines.size()).as("the corpus is read").isGreaterThan(60);
        for (Line line : lines) {
            Result result = EndpointRules.parse(line.address(), NONE);

            assertThat(result.ok()).as("must be refused: " + line.reason()).isFalse();
            assertThat(result.refusal()).as(line.reason()).isNotNull();
            if (!line.address().isEmpty()) {
                assertThat(result.refusal().words()).as(line.reason()).doesNotContain(line.address());
            }
        }
    }

    @Test
    void aNormalPublicAddressIsAcceptedAndWrittenOneWay() {
        Endpoint plain = EndpointRules.parse("https://api.example.com", NONE).endpoint();
        Endpoint withPort = EndpointRules.parse("HTTPS://API.Example.com:8443/v1/", NONE).endpoint();
        Endpoint literal = EndpointRules.parse("https://8.8.8.8/", NONE).endpoint();
        Endpoint v6 = EndpointRules.parse("https://[2606:4700::1111]:444/x", NONE).endpoint();

        assertThat(plain.host()).isEqualTo("api.example.com");
        assertThat(plain.port()).isEqualTo(443);
        assertThat(plain.basePath()).isEmpty();
        assertThat(DefinitionChecks.canonical(plain)).isEqualTo("https://api.example.com");
        assertThat(withPort.host()).isEqualTo("api.example.com");
        assertThat(withPort.port()).isEqualTo(8443);
        assertThat(withPort.basePath()).isEqualTo("/v1");
        assertThat(DefinitionChecks.canonical(withPort)).isEqualTo("https://api.example.com:8443/v1");
        assertThat(literal.host()).isEqualTo("8.8.8.8");
        assertThat(v6.hostHeader()).isEqualTo("[2606:4700::1111]:444");
        assertThat(plain.development()).isFalse();
    }

    @Test
    void aDevelopmentHostMayUsePlainHttpAndPrivateAddressesAndNoOtherHostMay() {
        Policy dev = new Policy(Set.of("127.0.0.1", "localhost"));

        Result local = EndpointRules.parse("http://127.0.0.1:8080/status", dev);
        assertThat(local.ok()).isTrue();
        assertThat(local.endpoint().development()).isTrue();
        assertThat(local.endpoint().secure()).isFalse();
        assertThat(local.endpoint().port()).isEqualTo(8080);
        assertThat(EndpointRules.parse("http://localhost/", dev).ok()).isTrue();

        assertThat(EndpointRules.parse("http://10.0.0.1/", dev).refusal()).isEqualTo(Refusal.SCHEME);
        assertThat(EndpointRules.parse("https://10.0.0.1/", dev).refusal()).isEqualTo(Refusal.ADDRESS);
        assertThat(EndpointRules.parse("http://127.0.0.2/", dev).ok()).isFalse();
        assertThat(EndpointRules.parse("http://example.com/", dev).ok()).isFalse();
    }

    @Test
    void aCheckPathMustStartWithASlashAndHaveNoDotsQueryOrSpaces() {
        assertThat(EndpointRules.validPath("/")).isTrue();
        assertThat(EndpointRules.validPath("/status")).isTrue();
        assertThat(EndpointRules.validPath("/v1/health/")).isTrue();
        assertThat(EndpointRules.validPath("status")).isFalse();
        assertThat(EndpointRules.validPath("/a/../b")).isFalse();
        assertThat(EndpointRules.validPath("/a?x=1")).isFalse();
        assertThat(EndpointRules.validPath("/a b")).isFalse();
        assertThat(EndpointRules.validPath("//a")).isFalse();
        assertThat(EndpointRules.validPath("/%2e%2e/")).isFalse();
        assertThat(EndpointRules.validPath(null)).isFalse();
        assertThat(EndpointRules.validPath("/" + "a".repeat(1100))).isFalse();
    }

    @Test
    void everyRefusalHasAStableKeyAndFixedWords() {
        for (Refusal refusal : Refusal.values()) {
            assertThat(refusal.key()).matches("endpoint_[a-z_]+");
            assertThat(refusal.words()).isNotBlank();
        }
        assertThat(Refusal.values()).extracting(Refusal::key).doesNotHaveDuplicates();
    }

    @Test
    void theAddressRangesTheDefenceRestsOnAreTheRightOnes() throws Exception {
        for (String blocked : List.of("0.0.0.0", "10.1.2.3", "100.100.0.1", "127.0.0.1", "169.254.169.254",
                "172.16.0.1", "172.31.0.1", "192.0.0.1", "192.168.0.1", "198.19.0.1", "224.0.0.1", "239.1.1.1",
                "255.255.255.255", "::", "::1", "fc00::1", "fd12::1", "fe80::1", "ff02::1", "2001:db8::1",
                "2002::1", "64:ff9b::1")) {
            assertThat(HostAddresses.isPublic(InetAddress.getByName(blocked))).as(blocked).isFalse();
        }
        for (String open : List.of("1.1.1.1", "8.8.8.8", "93.184.216.34", "172.15.255.255", "172.32.0.1",
                "100.63.255.255", "100.128.0.1", "198.17.0.1", "198.20.0.1", "2606:4700::1111",
                "2a00:1450:4001::1")) {
            assertThat(HostAddresses.isPublic(InetAddress.getByName(open))).as(open).isTrue();
        }
    }
}
