package app.platform.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

/** Story S3-SEC-18: every session cookie is HttpOnly, SameSite=Strict, Secure outside a developer machine, scoped. */
class AuthCookiesTest {

    @Test
    void everyCookieIsHttpOnlyStrictSecureAndLimitedToItsPath() {
        AuthCookies cookies = new AuthCookies(true);

        List<String> all = List.of(
                cookies.set(AuthCookies.LOGIN, "v", AuthCookies.LOGIN_PATH, Duration.ofMinutes(5)),
                cookies.set(AuthCookies.TRANSACTION, "v", AuthCookies.TRANSACTION_PATH, Duration.ofMinutes(5)),
                cookies.set(AuthCookies.ACCESS, "v", AuthCookies.ACCESS_PATH, Duration.ofMinutes(10)),
                cookies.set(AuthCookies.REFRESH, "v", AuthCookies.REFRESH_PATH, Duration.ofHours(8)));

        assertThat(all).allSatisfy(cookie -> assertThat(cookie)
                .contains("HttpOnly").contains("Secure").contains("SameSite=Strict").contains("Path=/api/v1"));
        assertThat(all.get(3)).contains("Path=/api/v1/auth;");
        assertThat(all.get(0)).contains("Path=/api/v1/oauth2/authorize");
    }

    @Test
    void onADeveloperMachineOnlyTheSecureFlagIsLeftOut() {
        String cookie = new AuthCookies(false).set(AuthCookies.ACCESS, "v", AuthCookies.ACCESS_PATH,
                Duration.ofMinutes(10));

        assertThat(cookie).doesNotContain("Secure").contains("HttpOnly").contains("SameSite=Strict");
    }

    @Test
    void signOutRemovesAllFourCookiesWithTheSameScope() {
        HttpHeaders headers = new AuthCookies(true).clearAll();

        assertThat(headers.get(HttpHeaders.SET_COOKIE)).hasSize(4)
                .allSatisfy(cookie -> assertThat(cookie).contains("Max-Age=0").contains("HttpOnly"));
    }
}
