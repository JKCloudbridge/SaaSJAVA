package app.platform.deployment.internal;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Behind a load balancer that appends to {@code X-Forwarded-For}, the caller's address is the entry the balancer added,
 * never the one the client wrote first (ADR-0286).
 */
class ForwardedForNormalizerTest {

    private static HttpServletRequest seenBy(int hops, String... headerLines) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.7");
        for (String line : headerLines) {
            request.addHeader(ForwardedForNormalizer.HEADER, line);
        }
        MockFilterChain chain = new MockFilterChain();
        new ForwardedForNormalizer(hops).doFilter(request, new MockHttpServletResponse(), chain);
        return (HttpServletRequest) chain.getRequest();
    }

    @Test
    void theEntryTheTrustedProxyAddedIsTheRightMostOne() {
        assertThat(ForwardedForNormalizer.entry("203.0.113.9", 1)).isEqualTo("203.0.113.9");
        assertThat(ForwardedForNormalizer.entry("6.6.6.6, 203.0.113.9", 1)).isEqualTo("203.0.113.9");
        assertThat(ForwardedForNormalizer.entry("6.6.6.6, 7.7.7.7, 203.0.113.9", 1)).isEqualTo("203.0.113.9");
    }

    @Test
    void severalTrustedProxiesAreCountedFromTheRight() {
        assertThat(ForwardedForNormalizer.entry("6.6.6.6, 203.0.113.9, 10.1.1.1", 2)).isEqualTo("203.0.113.9");
        assertThat(ForwardedForNormalizer.entry("203.0.113.9, 10.1.1.1", 2)).isEqualTo("203.0.113.9");
    }

    @Test
    void aChainShorterThanTheTrustedHopsGivesNothingAndNeverTheClientsOwnChoice() {
        assertThat(ForwardedForNormalizer.entry("6.6.6.6", 2)).isNull();
        assertThat(ForwardedForNormalizer.entry("", 1)).isNull();
        assertThat(ForwardedForNormalizer.entry(null, 1)).isNull();
        assertThat(ForwardedForNormalizer.entry("6.6.6.6,", 1)).as("an empty last entry is nothing").isNull();
        assertThat(ForwardedForNormalizer.entry("6.6.6.6", 0)).isNull();
    }

    @Test
    void theFilterReplacesAForgedHeaderByTheBalancersEntry() throws Exception {
        HttpServletRequest seen = seenBy(1, "1.2.3.4, 5.6.7.8, 203.0.113.9");

        assertThat(seen.getHeader("X-Forwarded-For")).isEqualTo("203.0.113.9");
        assertThat(Collections.list(seen.getHeaders("x-forwarded-for"))).containsExactly("203.0.113.9");
        assertThat(Collections.list(seen.getHeaderNames())).filteredOn("X-Forwarded-For"::equalsIgnoreCase)
                .hasSize(1);
        assertThat(seen.getRemoteAddr()).as("the connection is untouched").isEqualTo("10.0.0.7");
    }

    @Test
    void headerLinesAreJoinedBeforeTheyAreCounted() throws Exception {
        // Two header lines are one list: the balancer's entry is the last one of the last line.
        assertThat(seenBy(1, "6.6.6.6", "203.0.113.9").getHeader("X-Forwarded-For")).isEqualTo("203.0.113.9");
    }

    @Test
    void aRequestWithoutTheHeaderStaysWithout() throws Exception {
        HttpServletRequest seen = seenBy(1);

        assertThat(seen.getHeader("X-Forwarded-For")).isNull();
        assertThat(Collections.list(seen.getHeaders("X-Forwarded-For"))).isEmpty();
    }

    @Test
    void withZeroHopsNothingChangesAtAll() throws Exception {
        HttpServletRequest seen = seenBy(0, "1.2.3.4, 5.6.7.8");

        assertThat(seen.getHeader("X-Forwarded-For")).isEqualTo("1.2.3.4, 5.6.7.8");
    }

    @Test
    void otherHeadersAreNotTouched() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "6.6.6.6, 203.0.113.9");
        request.addHeader("X-Request-ID", "abc");
        MockFilterChain chain = new MockFilterChain();

        new ForwardedForNormalizer(1).doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(((HttpServletRequest) chain.getRequest()).getHeader("X-Request-ID")).isEqualTo("abc");
    }
}
