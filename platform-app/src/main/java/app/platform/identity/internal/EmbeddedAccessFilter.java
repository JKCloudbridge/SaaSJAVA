package app.platform.identity.internal;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthentication;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Holds a request made with the token of a custom page's script to {@link EmbeddedAccessPolicy} (Sprint 18, ADR-0127).
 * It runs right after the token has been checked, so a refusal is the ordinary 403 of the error model, with the request
 * ID, and nothing downstream (controllers, the tenant context, the database) has been touched. A request with any other
 * kind of token passes through unchanged. Not a bean: it belongs to the API filter chain.
 */
final class EmbeddedAccessFilter extends OncePerRequestFilter {

    private final ApiSecurityHandlers.Denied denied;

    EmbeddedAccessFilter(ApiSecurityHandlers.Denied denied) {
        this.denied = denied;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (isEmbedded(SecurityContextHolder.getContext().getAuthentication())
                && !EmbeddedAccessPolicy.permits(request.getMethod(), request.getRequestURI())) {
            // The words never say which rule refused: the page's author reads the documentation, not the error.
            denied.handle(request, response, new AccessDeniedException("embedded token"));
            return;
        }
        chain.doFilter(request, response);
    }

    /** Whether the caller proved itself with the token of a custom page's script. */
    static boolean isEmbedded(Authentication authentication) {
        return authentication instanceof BearerTokenAuthentication token
                && Boolean.TRUE.equals(token.getTokenAttributes().get(TokenAuthentication.EMBEDDED_ATTRIBUTE));
    }
}
