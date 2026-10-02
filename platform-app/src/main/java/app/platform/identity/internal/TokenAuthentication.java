package app.platform.identity.internal;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.DefaultOAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.introspection.BadOpaqueTokenException;
import org.springframework.security.oauth2.server.resource.introspection.OpaqueTokenIntrospector;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * How an API request proves who it is: an opaque access token, from the {@code Authorization: Bearer} header (other
 * programs) or from the access cookie (the browser). The header wins when both are present, and a bad header is never
 * rescued by a cookie.
 *
 * <p>The token is checked on every request against the database (story S3-SEC-14): see {@link AuthorizationStore}. The
 * answer is "alive or not" and nothing more; the reason a token is not alive (expired, revoked, user suspended, wrong
 * host) never reaches the caller.
 */
final class TokenAuthentication {

    private static final String AUTHORIZATION = "Authorization";
    private static final String BEARER = "Bearer ";
    private static final int MAX_TOKEN_LENGTH = 4096;

    /** The attribute of the authenticated principal that carries the membership on an organization host. */
    static final String MEMBERSHIP_ATTRIBUTE = "mid";

    private TokenAuthentication() {
    }

    /** The token in the {@code Authorization} header, if the header carries one. A malformed one is an error. */
    static Optional<String> fromHeader(HttpServletRequest request) {
        String header = request.getHeader(AUTHORIZATION);
        if (header == null || !header.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
            return Optional.empty();
        }
        String token = header.substring(BEARER.length()).strip();
        if (token.isEmpty() || token.length() > MAX_TOKEN_LENGTH) {
            throw new InvalidBearerTokenException("The token is not valid.");
        }
        return Optional.of(token);
    }

    /** The token a request presents, from the header or the access cookie (sign-out looks at both). */
    static Optional<String> presented(HttpServletRequest request) {
        Optional<String> header = fromHeader(request);
        return header.isPresent() ? header : AuthCookies.read(request, AuthCookies.ACCESS);
    }

    /**
     * Finds the token in the {@code Authorization} header only.
     *
     * <p>This is deliberate and matters for security: the framework exempts every request that carries a bearer token
     * from forgery protection (a page on another site cannot set that header), and it decides that with this resolver.
     * If the resolver also read the cookie, a cross-site form post, which the browser sends the cookie with, would be
     * exempted too. The cookie is turned into a header by {@link CookieTokenFilter} <em>after</em> the forgery check.
     */
    static final class Resolver implements BearerTokenResolver {

        @Override
        public String resolve(HttpServletRequest request) {
            return fromHeader(request).orElse(null);
        }
    }

    /**
     * Presents the access cookie to the rest of the chain as an {@code Authorization} header, when the request has no
     * header of its own. It runs after the forgery check, which therefore still sees a cookie-only request as one that
     * needs the forgery header (see {@link Resolver}).
     */
    static final class CookieTokenFilter extends OncePerRequestFilter {

        private final Predicate<HttpServletRequest> publicEndpoint;

        /**
         * @param publicEndpoint which requests are for a public endpoint: those never get the cookie turned into a
         *        token. A public endpoint must work for a browser that still holds a revoked or expired token (after
         *        a sign-out on another device, say): the person has to be able to sign in again, and the framework
         * would        otherwise answer 401 to any request that presents a token that is no longer alive.
         */
        CookieTokenFilter(Predicate<HttpServletRequest> publicEndpoint) {
            this.publicEndpoint = publicEndpoint;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            if (request.getHeader(AUTHORIZATION) == null && !publicEndpoint.test(request)) {
                Optional<String> cookie = AuthCookies.read(request, AuthCookies.ACCESS);
                if (cookie.isPresent()) {
                    chain.doFilter(new WithAuthorization(request, BEARER + cookie.get()), response);
                    return;
                }
            }
            chain.doFilter(request, response);
        }
    }

    /** A request that reports one extra header. */
    private static final class WithAuthorization extends HttpServletRequestWrapper {

        private final String value;

        WithAuthorization(HttpServletRequest request, String value) {
            super(request);
            this.value = value;
        }

        @Override
        public String getHeader(String name) {
            return AUTHORIZATION.equalsIgnoreCase(name) ? value : super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            return AUTHORIZATION.equalsIgnoreCase(name)
                    ? Collections.enumeration(List.of(value)) : super.getHeaders(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            Set<String> names = new LinkedHashSet<>(Collections.list(super.getHeaderNames()));
            names.add(AUTHORIZATION);
            return Collections.enumeration(names);
        }
    }

    /** Asks the store whether the token is alive. */
    static final class Introspector implements OpaqueTokenIntrospector {

        private final AuthorizationStore store;
        private final MembershipGate gate;
        private final AuthAudit audit;
        private final PlatformRoleRepository platformRoles;
        private final Clock clock;
        private final Duration platformSessionMax;

        Introspector(AuthorizationStore store, MembershipGate gate, AuthAudit audit,
                PlatformRoleRepository platformRoles, Clock clock, Duration platformSessionMax) {
            this.store = store;
            this.gate = gate;
            this.audit = audit;
            this.platformRoles = platformRoles;
            this.clock = clock;
            this.platformSessionMax = platformSessionMax;
        }

        @Override
        public OAuth2AuthenticatedPrincipal introspect(String token) {
            AuthorizationStore.Introspected found = store.introspect(token)
                    .orElseThrow(() -> new BadOpaqueTokenException("The token is not valid."));
            List<GrantedAuthority> none = List.of();
            Map<String, Object> attributes = new java.util.HashMap<>(Map.of("sub", found.userId().toString(),
                    "sid", found.authorizationId().toString(), "scope", List.copyOf(found.scopes())));
            if (gate.onOrganizationHost()) {
                // A token of an organization host is good only while its holder is an active member (ADR-0026).
                // Ending a membership revokes the grants as well; this is the check that does not depend on that.
                MembershipRepository.Own own = gate.activeMembership(found.userId()).orElse(null);
                if (own == null) {
                    audit.bindingRefused(found.userId(), "not_a_member");
                    throw new BadOpaqueTokenException("The token is not valid.");
                }
                attributes.put(MEMBERSHIP_ATTRIBUTE, own.id().toString());
            } else if (found.startedAt().plus(platformSessionMax).isBefore(clock.instant())
                    && !platformRoles.rolesOf(found.userId()).isEmpty()) {
                // The platform host with a platform role: a short session (ADR-0030). The person signs in again;
                // nothing says why to the caller.
                audit.bindingRefused(found.userId(), "platform_session_too_old");
                throw new BadOpaqueTokenException("The token is not valid.");
            }
            return new DefaultOAuth2AuthenticatedPrincipal(found.userId().toString(), attributes, none);
        }
    }
}
