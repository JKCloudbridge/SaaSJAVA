package app.platform.identity.internal;

import app.platform.tenant.TenantContext;
import app.platform.tenant.TenantContexts;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthentication;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Puts the authenticated user into the tenant context of the request (Sprint 3: the user comes from the authenticated
 * identity; the tenant still comes from the host name only).
 *
 * <p>Runs right after the token has been checked. When the request has a tenant (an organization host) the context is
 * re-opened for the same tenant with the user filled in; the tenant is read from the context the host name produced and
 * never from anything the client sent. On the platform host there is no tenant, and the context cannot exist without
 * one, so the user is visible only through the security context there. The membership is not looked up (Sprint 5).
 * Not a bean: it belongs to the API filter chain, and a servlet-container registration of it would run it twice.
 */
final class UserContextFilter extends OncePerRequestFilter {

    private final TenantContexts contexts;

    UserContextFilter(TenantContexts contexts) {
        this.contexts = contexts;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Optional<TenantContext> current = contexts.current();
        Optional<UUID> user = authenticatedUser();
        if (current.isEmpty() || user.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }
        TenantContext withUser = new TenantContext(current.get().tenantId(), user.get(), null);
        try (TenantContexts.Scope _ = contexts.open(withUser)) {
            chain.doFilter(request, response);
        }
    }

    private static Optional<UUID> authenticatedUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof BearerTokenAuthentication token && token.isAuthenticated()) {
            try {
                return Optional.of(UUID.fromString(token.getName()));
            } catch (IllegalArgumentException e) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }
}
