package app.platform.identity.internal;

import app.platformapi.ApiPaths;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.endpoint.PkceParameterNames;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AccessTokenAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2RefreshTokenAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2RefreshTokenAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.context.AuthorizationServerContext;
import org.springframework.security.oauth2.server.authorization.context.AuthorizationServerContextHolder;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;
import org.springframework.stereotype.Component;

/**
 * The browser side of the standard sign-in flow (decision of Sprint 3, ADR-0019): after the password check, the
 * platform runs the authorization-code flow with PKCE against its own authorization server, so the browser ends with
 * cookies and no script ever holds a token.
 *
 * <ol>
 *   <li>{@link #start}: makes a one-time state and a PKCE verifier, keeps them in a cookie, and sends the browser to
 * the       authorization endpoint with the challenge.</li>
 *   <li>The authorization endpoint (the library) finds the sign-in through the login cookie and answers with a one-time
 *       code, back to this host's {@link #callback}.</li>
 *   <li>{@link #callback}: checks the state, exchanges the code (with the verifier, which proves the same browser
 *       started it) for the tokens, and sets the session cookies.</li>
 *   <li>{@link #refresh}: replaces the tokens using the refresh cookie (rotation, reuse detection).</li>
 *   <li>{@link #signOut}: revokes what the browser presents and removes the cookies.</li>
 * </ol>
 * The code exchange and the refresh run in-process on the library's own authentication providers, so they use exactly
 * the same rules, storage and token generator as the standard token endpoint. The redirect always goes back to the host
 * the person is on, which is how the host-bound grant stays on its host.
 */
@Component
class AuthFlow {

    private static final Logger LOG = LoggerFactory.getLogger(AuthFlow.class);
    private static final ClientAuthenticationMethod INTERNAL_METHOD =
            new ClientAuthenticationMethod("platform_internal");
    private static final String SIGN_IN_PAGE = "/sign-in";
    private static final String DEFAULT_LANDING = "/";
    private static final int MAX_CONTINUE_LENGTH = 200;

    private final RegisteredClientRepository clients;
    private final AuthorizationStore store;
    private final LoginSessions loginSessions;
    private final AuthCookies cookies;
    private final AuthAudit audit;
    private final AuthorizationServerSettings serverSettings;
    private final IdentityProperties properties;
    private final OAuth2AuthorizationCodeAuthenticationProvider codeProvider;
    private final OAuth2RefreshTokenAuthenticationProvider refreshProvider;
    private final boolean trustForwardedHost;

    AuthFlow(RegisteredClientRepository clients, OAuth2AuthorizationService authorizations, AuthorizationStore store,
            OAuth2TokenGenerator<?> tokenGenerator, LoginSessions loginSessions, AuthCookies cookies,
            AuthAudit audit, AuthorizationServerSettings serverSettings, IdentityProperties properties,
            @org.springframework.beans.factory.annotation.Value("${platform.tenancy.trust-forwarded-host:false}")
            boolean trustForwardedHost) {
        this.clients = clients;
        this.store = store;
        this.loginSessions = loginSessions;
        this.cookies = cookies;
        this.audit = audit;
        this.serverSettings = serverSettings;
        this.properties = properties;
        this.codeProvider = new OAuth2AuthorizationCodeAuthenticationProvider(authorizations, tokenGenerator);
        this.refreshProvider = new OAuth2RefreshTokenAuthenticationProvider(authorizations, tokenGenerator);
        this.trustForwardedHost = trustForwardedHost;
    }

    // ---- step 1: send the browser to the authorization endpoint ----

    ResponseEntity<Void> start(HttpServletRequest request, String continuePath) {
        String state = Hashes.randomSecret();
        String verifier = Hashes.randomSecret();
        String challenge = challengeOf(verifier);
        String landing = safeLandingPath(continuePath);
        String transaction = state + "~" + verifier + "~"
                + Base64.getUrlEncoder().withoutPadding().encodeToString(landing.getBytes(StandardCharsets.UTF_8));
        String authorize = ApiPaths.OAUTH2_AUTHORIZE
                + "?response_type=code&client_id=" + encode(WebClient.CLIENT_ID)
                + "&scope=openid"
                + "&redirect_uri=" + encode(callbackUri(request))
                + "&state=" + encode(state)
                + "&code_challenge=" + encode(challenge)
                + "&code_challenge_method=S256";
        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, authorize)
                .header(HttpHeaders.SET_COOKIE,
                        cookies.set(AuthCookies.TRANSACTION, transaction, AuthCookies.TRANSACTION_PATH,
                                Duration.ofMinutes(5)))
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .build();
    }

    // ---- step 3: the return from the authorization endpoint ----

    ResponseEntity<Void> callback(HttpServletRequest request, String code, String state, String error) {
        Optional<String> transaction = AuthCookies.read(request, AuthCookies.TRANSACTION);
        String[] parts = transaction.map(value -> value.split("~", -1)).orElse(new String[0]);
        boolean stateMatches = parts.length == 3 && state != null
                && MessageDigest.isEqual(parts[0].getBytes(StandardCharsets.UTF_8),
                        state.getBytes(StandardCharsets.UTF_8));
        if (error != null || code == null || !stateMatches) {
            LOG.info("Sign-in return refused");
            return failedReturn();
        }
        String landing = new String(Base64.getUrlDecoder().decode(parts[2]), StandardCharsets.UTF_8);
        try {
            OAuth2AccessTokenAuthenticationToken tokens = exchangeCode(request, code, parts[1]);
            ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.FOUND)
                    .header(HttpHeaders.LOCATION, safeLandingPath(landing))
                    .header(HttpHeaders.CACHE_CONTROL, "no-store");
            setSessionCookies(response, tokens);
            response.header(HttpHeaders.SET_COOKIE,
                    cookies.clear(AuthCookies.TRANSACTION, AuthCookies.TRANSACTION_PATH));
            response.header(HttpHeaders.SET_COOKIE, cookies.clear(AuthCookies.LOGIN, AuthCookies.LOGIN_PATH));
            // The login cookie has done its job; end it on the server as well.
            AuthCookies.read(request, AuthCookies.LOGIN).ifPresent(loginSessions::revoke);
            return response.build();
        } catch (OAuth2AuthenticationException e) {
            LOG.info("Sign-in code exchange refused ({})", e.getError().getErrorCode());
            return failedReturn();
        }
    }

    private ResponseEntity<Void> failedReturn() {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, SIGN_IN_PAGE + "?problem=sign-in")
                .header(HttpHeaders.CACHE_CONTROL, "no-store");
        cookies.clearAll().forEach((name, values) -> values.forEach(value -> response.header(name, value)));
        return response.build();
    }

    // ---- refresh ----

    /** Replaces the tokens. @return 204 with new cookies, or 401 with the cookies removed */
    ResponseEntity<Void> refresh(HttpServletRequest request) {
        Optional<String> refreshCookie = AuthCookies.read(request, AuthCookies.REFRESH);
        if (refreshCookie.isEmpty()) {
            return unauthenticated();
        }
        Authentication principal = internalClient();
        try {
            OAuth2AccessTokenAuthenticationToken tokens = withServerContext(request, () ->
                    (OAuth2AccessTokenAuthenticationToken) refreshProvider.authenticate(
                            new OAuth2RefreshTokenAuthenticationToken(refreshCookie.get(), principal, Set.of(),
                                    Map.of())));
            ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.NO_CONTENT)
                    .header(HttpHeaders.CACHE_CONTROL, "no-store");
            setSessionCookies(response, tokens);
            return response.build();
        } catch (OAuth2AuthenticationException e) {
            LOG.info("Refresh refused ({})", e.getError().getErrorCode());
            if (store.consumeBenignRefusal()) {
                // Another request has just replaced this refresh token: keep the cookies it set and let the caller
                // retry.
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).header(HttpHeaders.CACHE_CONTROL, "no-store")
                        .build();
            }
            return unauthenticated();
        }
    }

    private ResponseEntity<Void> unauthenticated() {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.CACHE_CONTROL, "no-store");
        cookies.clearAll().forEach((name, values) -> values.forEach(value -> response.header(name, value)));
        return response.build();
    }

    // ---- sign-out ----

    /**
     * Revokes whatever the browser presents (the access token, the refresh token, the login cookie) and returns the
     * headers that remove the cookies. Idempotent: signing out when not signed in is not an error.
     */
    HttpHeaders signOut(HttpServletRequest request, Optional<String> bearerOrCookieAccessToken) {
        bearerOrCookieAccessToken.flatMap(store::introspect).ifPresent(grant -> {
            if (store.revoke(grant.authorizationId(), "sign_out", grant.userId())) {
                audit.signedOut(grant.userId());
            }
        });
        AuthCookies.read(request, AuthCookies.REFRESH).flatMap(token -> store.revokeByRefreshToken(token, "sign_out"))
                .ifPresent(audit::signedOut);
        AuthCookies.read(request, AuthCookies.LOGIN).ifPresent(loginSessions::revoke);
        return cookies.clearAll();
    }

    // ---- internals ----

    private OAuth2AccessTokenAuthenticationToken exchangeCode(HttpServletRequest request, String code,
            String verifier) {
        Authentication principal = internalClient();
        return withServerContext(request, () -> (OAuth2AccessTokenAuthenticationToken) codeProvider.authenticate(
                new OAuth2AuthorizationCodeAuthenticationToken(code, principal, callbackUri(request),
                        Map.of(PkceParameterNames.CODE_VERIFIER, verifier))));
    }

    private void setSessionCookies(ResponseEntity.BodyBuilder response, OAuth2AccessTokenAuthenticationToken tokens) {
        Instant now = Instant.now();
        Duration accessLife = Duration.between(now, tokens.getAccessToken().getExpiresAt());
        response.header(HttpHeaders.SET_COOKIE, cookies.set(AuthCookies.ACCESS, tokens.getAccessToken().getTokenValue(),
                AuthCookies.ACCESS_PATH, accessLife));
        OAuth2RefreshToken refresh = tokens.getRefreshToken();
        if (refresh != null) {
            Duration refreshLife = refresh.getExpiresAt() == null
                    ? properties.tokens().refreshIdle() : Duration.between(now, refresh.getExpiresAt());
            response.header(HttpHeaders.SET_COOKIE, cookies.set(AuthCookies.REFRESH, refresh.getTokenValue(),
                    AuthCookies.REFRESH_PATH, refreshLife));
        }
    }

    /**
     * The client as it appears when the platform's own server code makes the exchange. The library issues refresh
     * tokens only to a client that is not a public one, and the exchange here is made by the platform's backend on
     * behalf of the browser (the browser itself never talks to the token endpoint and never holds a secret), so the
     * call is marked as an internal one. PKCE still binds the code to the browser that started the sign-in.
     */
    private Authentication internalClient() {
        return new OAuth2ClientAuthenticationToken(client(), INTERNAL_METHOD, null);
    }

    private RegisteredClient client() {
        RegisteredClient client = clients.findByClientId(WebClient.CLIENT_ID);
        if (client == null) {
            throw new IllegalStateException("The web client is not registered");
        }
        return client;
    }

    /** The library's providers read the issuer and settings from a holder the library's own filter fills. */
    private <T> T withServerContext(HttpServletRequest request, java.util.function.Supplier<T> work) {
        String issuer = (properties.cookies().secure() ? "https://" : "http://")
                + RequestHost.authority(request, trustForwardedHost);
        AuthorizationServerContextHolder.setContext(new AuthorizationServerContext() {
            @Override
            public String getIssuer() {
                return issuer;
            }

            @Override
            public AuthorizationServerSettings getAuthorizationServerSettings() {
                return serverSettings;
            }
        });
        try {
            return work.get();
        } finally {
            AuthorizationServerContextHolder.resetContext();
        }
    }

    /** The address the authorization endpoint returns to: this host, always. */
    private String callbackUri(HttpServletRequest request) {
        String scheme = properties.cookies().secure() ? "https" : "http";
        return URI.create(scheme + "://" + RequestHost.authority(request, trustForwardedHost)
                + ApiPaths.AUTH_CALLBACK).toString();
    }

    /** Only a path on this site: an address with two leading slashes or another site would be an open redirect. */
    static String safeLandingPath(String candidate) {
        if (candidate == null || candidate.isEmpty() || candidate.length() > MAX_CONTINUE_LENGTH
                || candidate.charAt(0) != '/' || candidate.startsWith("//") || candidate.contains("\\")
                || candidate.chars().anyMatch(c -> c < 0x20 || c == 0x7f)) {
            return DEFAULT_LANDING;
        }
        return candidate;
    }

    private static String challengeOf(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static String encode(String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8);
    }

    /** The one first-party client of Sprint 3. */
    static final class WebClient {

        static final String CLIENT_ID = "platform-web";

        private WebClient() {
        }
    }
}
