package app.platform.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class ClientSourceTest {

    private static MockHttpServletRequest request(String remote, String forwardedFor) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remote);
        if (forwardedFor != null) {
            request.addHeader("X-Forwarded-For", forwardedFor);
        }
        return request;
    }

    @Test
    void theConnectionAddressIsUsedAndAForwardedHeaderIsIgnoredUnlessTrusted() {
        assertThat(ClientSource.of(request("203.0.113.5", "198.51.100.1"), false)).isEqualTo("203.0.113.5");
    }

    @Test
    void behindATrustedProxyTheFirstForwardedAddressIsUsed() {
        assertThat(ClientSource.of(request("10.0.0.2", "198.51.100.1, 10.0.0.9"), true)).isEqualTo("198.51.100.1");
    }

    @Test
    void anIpv6AddressIsReducedToItsSlash64SoRotatingWithinOneSubscriberDoesNotHelp() {
        String a = ClientSource.normalize("2001:db8:1:2:aaaa:bbbb:cccc:dddd");
        String b = ClientSource.normalize("2001:db8:1:2:1111:2222:3333:4444");

        assertThat(a).isEqualTo(b).endsWith("/64");
        assertThat(ClientSource.normalize("2001:db8:1:3::1")).isNotEqualTo(a);
    }

    @Test
    void textThatIsNotAnAddressBecomesOneSharedBucketAndIsNeverResolvedAsAName() {
        assertThat(ClientSource.normalize("attacker.example.test")).isEqualTo("unknown");
        assertThat(ClientSource.normalize("1.2.3.4; drop table")).isEqualTo("unknown");
        assertThat(ClientSource.normalize(null)).isEqualTo("unknown");
        assertThat(ClientSource.of(request("203.0.113.5", "not an address"), true)).isEqualTo("unknown");
    }
}
