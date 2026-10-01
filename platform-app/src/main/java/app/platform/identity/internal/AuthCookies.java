package app.platform.identity.internal;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;

/**
 * The cookies that carry a browser session (story S3-SEC-18, ADR-0019). Every one is HttpOnly (script on the page
 * cannot read it), SameSite=Strict (the browser never sends it from another site) and Secure (outside a developer
 * machine); each is limited to the paths that need it, so a token does not travel with requests that do not.
 *
 * <ul>
 *   <li>{@code platform_login}: proves the password check, only until the authorization-code step has finished.</li>
 *   <li>{@code platform_tx}: the state and the PKCE verifier of one sign-in, only until it returns.</li>
 *   <li>{@code platform_at}: the access token, sent with every API call.</li>
 *   <li>{@code platform_rt}: the refresh token, sent only to the refresh and sign-out endpoints.</li>
 * </ul>
 * Cookies on a developer machine have no Secure flag because the browser address is plain HTTP.
 */
final class AuthCookies {

    static final String LOGIN = "platform_login";
    static final String TRANSACTION = "platform_tx";
    static final String ACCESS = "platform_at";
    static final String REFRESH = "platform_rt";

    static final String LOGIN_PATH = "/api/v1/oauth2/authorize";
    static final String TRANSACTION_PATH = "/api/v1/auth/callback";
    static final String ACCESS_PATH = "/api/v1";
    static final String REFRESH_PATH = "/api/v1/auth";

    private final boolean secure;

    AuthCookies(boolean secure) {
        this.secure = secure;
    }

    /** The value of a cookie of the request, if it was sent and is not empty. */
    static Optional<String> read(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName()) && cookie.getValue() != null && !cookie.getValue().isEmpty()) {
                return Optional.of(cookie.getValue());
            }
        }
        return Optional.empty();
    }

    String set(String name, String value, String path, Duration maxAge) {
        return ResponseCookie.from(name, value).httpOnly(true).secure(secure).sameSite("Strict").path(path)
                .maxAge(maxAge).build().toString();
    }

    String clear(String name, String path) {
        return ResponseCookie.from(name, "").httpOnly(true).secure(secure).sameSite("Strict").path(path)
                .maxAge(Duration.ZERO).build().toString();
    }

    /** Header values that remove every session cookie. */
    HttpHeaders clearAll() {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.SET_COOKIE, clear(LOGIN, LOGIN_PATH));
        headers.add(HttpHeaders.SET_COOKIE, clear(TRANSACTION, TRANSACTION_PATH));
        headers.add(HttpHeaders.SET_COOKIE, clear(ACCESS, ACCESS_PATH));
        headers.add(HttpHeaders.SET_COOKIE, clear(REFRESH, REFRESH_PATH));
        return headers;
    }
}
