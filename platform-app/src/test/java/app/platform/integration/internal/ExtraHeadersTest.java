package app.platform.integration.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The headers a connector may ask for (Sprint 29, ADR-0228): plain ones, never one the platform sets itself. */
class ExtraHeadersTest {

    @Test
    void aPlainHeaderIsAccepted() {
        assertThat(ExtraHeaders.safe(Map.of("SOAPAction", "\"urn:Create\""))).isTrue();
        assertThat(ExtraHeaders.safe(Map.of("Idempotency-Key", "0190-abc:1"))).isTrue();
        assertThat(ExtraHeaders.safe(Map.of())).isTrue();
    }

    @Test
    void aHeaderThePlatformSetsItselfOrThatCarriesACredentialIsRefused() {
        for (String name : new String[] {"Host", "content-length", "Content-Type", "Authorization", "Cookie",
                "Transfer-Encoding", "Connection", "X-Forwarded-For", "Proxy-Authorization", "Location"}) {
            assertThat(ExtraHeaders.safe(Map.of(name, "x"))).as(name).isFalse();
        }
    }

    @Test
    void aNameOrValueThatCouldSmuggleALineIsRefused() {
        assertThat(ExtraHeaders.safe(Map.of("X-A\r\nX-B", "x"))).isFalse();
        assertThat(ExtraHeaders.safe(Map.of("X-A", "x\r\nX-B: y"))).isFalse();
        assertThat(ExtraHeaders.safe(Map.of("X-A", "café"))).isFalse();
        assertThat(ExtraHeaders.safe(Map.of("X-A", ""))).isFalse();
        assertThat(ExtraHeaders.safe(Map.of("1X", "x"))).isFalse();
    }

    @Test
    void tooManyHeadersAreRefused() {
        Map<String, String> many = new LinkedHashMap<>();
        for (int i = 0; i < 9; i++) {
            many.put("X-Extra-" + i, "x");
        }

        assertThat(ExtraHeaders.safe(many)).isFalse();
    }
}
