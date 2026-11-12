package app.platform.identity.internal;

import app.platformapi.ApiPaths;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthentication;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Holds a request to the public API to {@link PublicApiAccess} right after the token has been checked (Sprint 30,
 * ADR-0249): a refusal is the ordinary 403 of the error model with the request ID, and nothing downstream has been
 * touched. Requests outside the public prefix, and requests that match no operation of the table, pass through
 * unchanged. Not a bean: it belongs to the API filter chain.
 */
final class PublicApiAccessFilter extends OncePerRequestFilter {

    /**
     * The attribute a token carries once tokens are granted API scopes (Sprint 31). No token has it today. It is not
     * the standard {@code scope} claim: the tokens of the web application carry that one for their sign-in, and it
     * says nothing about what the public API may be asked.
     */
    static final String SCOPE_ATTRIBUTE = "api_scopes";

    private final ApiSecurityHandlers.Denied denied;

    PublicApiAccessFilter(ApiSecurityHandlers.Denied denied) {
        this.denied = denied;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !ApiPaths.isPublicApiPath(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Optional<PublicApiAccess.Operation> operation =
                PublicApiAccess.operationOf(request.getMethod(), request.getRequestURI());
        if (operation.isPresent()
                && !PublicApiAccess.permits(operation.get(), scopesOf(SecurityContextHolder.getContext()
                        .getAuthentication()))) {
            // The words never say which scope was missing: the integrator reads the documentation, not the error.
            denied.handle(request, response, new AccessDeniedException("scope"));
            return;
        }
        chain.doFilter(request, response);
    }

    /** The scopes the token carries; empty for a token without any (every token today). */
    static Optional<Set<String>> scopesOf(Authentication authentication) {
        if (!(authentication instanceof BearerTokenAuthentication token)) {
            return Optional.empty();
        }
        Object scope = token.getTokenAttributes().get(SCOPE_ATTRIBUTE);
        if (scope instanceof Collection<?> values) {
            Set<String> granted = new LinkedHashSet<>();
            values.forEach(value -> granted.add(String.valueOf(value)));
            return Optional.of(Set.copyOf(granted));
        }
        if (scope instanceof String text) {
            return Optional.of(Set.of(text.trim().split("\\s+")));
        }
        return Optional.empty();
    }
}
