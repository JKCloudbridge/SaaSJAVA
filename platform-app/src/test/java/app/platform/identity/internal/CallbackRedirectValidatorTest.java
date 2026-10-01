package app.platform.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Story S3-SEC-18 (redirect side): the one-time code can only go back to this host's callback.
 */
class CallbackRedirectValidatorTest {

    private static void requestTo(String host) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Host", host);
        request.setServerName(host.contains(":") ? host.substring(0, host.indexOf(':')) : host);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach
    void clear() {
        RequestContextHolder.resetRequestAttributes();
    }

    private final CallbackRedirectValidator secure = new CallbackRedirectValidator(false, true);

    @Test
    void thisHostsCallbackOverHttpsIsAllowed() {
        requestTo("tenant-a.platform.example.test");

        assertThat(secure.allowed("https://tenant-a.platform.example.test/api/v1/auth/callback")).isTrue();
        assertThat(secure.allowed("https://TENANT-A.platform.example.test:8443/api/v1/auth/callback")).isTrue();
    }

    @Test
    void anotherHostIsRefused() {
        requestTo("tenant-a.platform.example.test");

        assertThat(secure.allowed("https://tenant-b.platform.example.test/api/v1/auth/callback")).isFalse();
        assertThat(secure.allowed("https://evil.example.test/api/v1/auth/callback")).isFalse();
        assertThat(secure.allowed("https://tenant-a.platform.example.test.evil.example.test/api/v1/auth/callback"))
                .isFalse();
    }

    @Test
    void anotherPathOrExtraPartsAreRefused() {
        requestTo("tenant-a.platform.example.test");
        String host = "https://tenant-a.platform.example.test";

        assertThat(secure.allowed(host + "/api/v1/auth/callback/../../x")).isFalse();
        assertThat(secure.allowed(host + "/other")).isFalse();
        assertThat(secure.allowed(host + "/api/v1/auth/callback?next=https://evil.example.test")).isFalse();
        assertThat(secure.allowed(host + "/api/v1/auth/callback#fragment")).isFalse();
        assertThat(secure.allowed("https://user:pw@tenant-a.platform.example.test/api/v1/auth/callback")).isFalse();
    }

    @Test
    void theSchemeMustBeTheOneThePlatformUses() {
        requestTo("tenant-a.platform.example.test");

        assertThat(secure.allowed("http://tenant-a.platform.example.test/api/v1/auth/callback")).isFalse();
        assertThat(new CallbackRedirectValidator(false, false)
                .allowed("https://tenant-a.platform.example.test/api/v1/auth/callback")).isFalse();
        assertThat(secure.allowed("javascript:alert(1)")).isFalse();
    }

    @Test
    void junkAndMissingValuesAreRefused() {
        requestTo("tenant-a.platform.example.test");

        assertThat(secure.allowed(null)).isFalse();
        assertThat(secure.allowed("")).isFalse();
        assertThat(secure.allowed("not a uri at all ::")).isFalse();
        assertThat(secure.allowed("/api/v1/auth/callback")).isFalse();
    }

    @Test
    void theForwardedHostIsHonouredOnlyWhenTrusted() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Host", "localhost:8080");
        request.setServerName("localhost");
        request.addHeader("X-Forwarded-Host", "tenant-a.localhost:3000");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        String callback = "http://tenant-a.localhost:3000/api/v1/auth/callback";

        assertThat(new CallbackRedirectValidator(true, false).allowed(callback)).isTrue();
        assertThat(new CallbackRedirectValidator(false, false).allowed(callback)).isFalse();
    }
}
