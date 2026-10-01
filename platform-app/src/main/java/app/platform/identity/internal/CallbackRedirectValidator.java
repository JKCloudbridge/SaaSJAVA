package app.platform.identity.internal;

import app.platformapi.ApiPaths;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.function.Consumer;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationContext;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationException;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationValidator;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Decides where the authorization endpoint may send the one-time code (story S3-SEC-18).
 *
 * <p>The library compares the redirect address with a fixed list. The platform has one host per organization, so a
 * fixed list cannot work; instead the address must be exactly this host's callback: the same host name as the request,
 * the callback path and nothing else (no user information, no query, no fragment), and the scheme the platform uses
 * ({@code https} outside a developer machine). A code can therefore never be sent to another host, which is also what
 * keeps a grant on the host it was issued on.
 */
final class CallbackRedirectValidator implements Consumer<OAuth2AuthorizationCodeRequestAuthenticationContext> {

    private final boolean trustForwardedHost;
    private final boolean secureScheme;

    CallbackRedirectValidator(boolean trustForwardedHost, boolean secureScheme) {
        this.trustForwardedHost = trustForwardedHost;
        this.secureScheme = secureScheme;
    }

    @Override
    public void accept(OAuth2AuthorizationCodeRequestAuthenticationContext context) {
        // The library's own scope check first.
        OAuth2AuthorizationCodeRequestAuthenticationValidator.DEFAULT_SCOPE_VALIDATOR.accept(context);
        // At this stage of the library's check only the request itself exists, not yet an authorization request.
        OAuth2AuthorizationCodeRequestAuthenticationToken request = context.getAuthentication();
        if (!allowed(request.getRedirectUri())) {
            throw invalidRedirect();
        }
    }

    boolean allowed(String redirect) {
        if (redirect == null) {
            return false;
        }
        URI uri;
        try {
            uri = new URI(redirect);
        } catch (URISyntaxException e) {
            return false;
        }
        HttpServletRequest request = currentRequest();
        if (request == null || uri.getHost() == null) {
            return false;
        }
        String expectedScheme = secureScheme ? "https" : "http";
        return expectedScheme.equals(uri.getScheme())
                && uri.getRawUserInfo() == null
                && uri.getRawQuery() == null
                && uri.getRawFragment() == null
                && ApiPaths.AUTH_CALLBACK.equals(uri.getRawPath())
                && uri.getHost().equalsIgnoreCase(RequestHost.name(request, trustForwardedHost));
    }

    private static HttpServletRequest currentRequest() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        return attributes instanceof ServletRequestAttributes servlet ? servlet.getRequest() : null;
    }

    private static OAuth2AuthenticationException invalidRedirect() {
        OAuth2Error error = new OAuth2Error(OAuth2ErrorCodes.INVALID_REQUEST,
                "OAuth 2.0 Parameter: " + OAuth2ParameterNames.REDIRECT_URI, null);
        // No redirect to the rejected address: the error is shown by the endpoint itself.
        return new OAuth2AuthorizationCodeRequestAuthenticationException(error, null);
    }
}
