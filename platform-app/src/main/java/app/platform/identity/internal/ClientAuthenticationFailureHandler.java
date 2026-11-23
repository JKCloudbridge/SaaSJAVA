package app.platform.identity.internal;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;

/**
 * What the token endpoint answers when a client could not be proved (Sprint 31, ADR-0268): the standard error document
 * of the protocol, with fixed words. Every cause that is not "too many failed attempts" is {@code invalid_client} with
 * status 401, whether the application does not exist, the secret is wrong, the application is off, the host is another
 * organization's, the member can no longer act, the plan lacks the feature or the switch is off. A caller who has used
 * up the failed attempts is told to wait: 429 with {@code Retry-After}. The error text of the library or of an
 * exception is never shown.
 */
final class ClientAuthenticationFailureHandler implements AuthenticationFailureHandler {

    private final long retryAfterSeconds;

    ClientAuthenticationFailureHandler(long retryAfterSeconds) {
        this.retryAfterSeconds = retryAfterSeconds;
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException exception) throws IOException {
        String code = exception instanceof OAuth2AuthenticationException oauth && oauth.getError() != null
                ? oauth.getError().getErrorCode() : OAuth2ErrorCodes.INVALID_CLIENT;
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader(HttpHeaders.PRAGMA, "no-cache");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        String body;
        if (ClientAuthenticationProvider.LIMITED.equals(code)) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
            body = "{\"error\":\"temporarily_unavailable\",\"error_description\":\"Too many failed attempts. "
                    + "Try again later.\"}";
        } else if (!OAuth2ErrorCodes.INVALID_CLIENT.equals(code)) {
            response.setStatus(HttpStatus.BAD_REQUEST.value());
            // The protocol's own code (invalid_grant for a wrong verifier, invalid_request, ...), never a text.
            String shown = code.matches("[a-z_]{1,40}") ? code : "invalid_request";
            body = "{\"error\":\"" + shown + "\",\"error_description\":\"The request is not valid.\"}";
        } else {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"platform\"");
            body = "{\"error\":\"invalid_client\",\"error_description\":\"Client authentication failed.\"}";
        }
        response.getWriter().write(body);
    }
}
