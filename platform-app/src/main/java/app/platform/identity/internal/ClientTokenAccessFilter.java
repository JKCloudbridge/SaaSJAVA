package app.platform.identity.internal;

import app.platform.sharedkernel.logging.LogContext;
import app.platformapi.ApiPaths;
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
 * Holds the token of a connected application to the public API and to nothing else (Sprint 31, ADR-0271): the mirror
 * image of {@link EmbeddedAccessFilter}. It runs right after the token has been checked, so a refusal is the ordinary
 * 403 of the error model with the request ID, and nothing downstream (controllers, the tenant context, the database)
 * has been touched.
 *
 * <p>Without this rule a connected application's token would be a full session of the member it acts as: the internal
 * interface has several hundred paths, nearly all of them setup and administration that other people's software must
 * never reach whatever the member could do, and the management of connected applications is among them. The rule is the
 * list of what is allowed ({@code /api/public/v1/} and nothing else), so a path added later is refused until somebody
 * decides. A path with an escape, a semicolon, a backslash, a dot segment or an empty segment is refused outright
 * (the web firewall already refuses most of them, this is the second line).
 *
 * <p>The filter also tells the rest of the request who the caller is: the public identifier of the application goes
 * into the logging context and onto the request, so the request's log line and every audit record written while it
 * is handled name the application next to the member. A person's own token passes through unchanged. Not a bean: it belongs to the
 * API filter chain.
 */
final class ClientTokenAccessFilter extends OncePerRequestFilter {

    private final ApiSecurityHandlers.Denied denied;

    ClientTokenAccessFilter(ApiSecurityHandlers.Denied denied) {
        this.denied = denied;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String client = clientOf(SecurityContextHolder.getContext().getAuthentication());
        if (client == null) {
            chain.doFilter(request, response);
            return;
        }
        if (!permits(request.getRequestURI())) {
            // The words never say which rule refused: the integrator reads the documentation, not the error.
            denied.handle(request, response, new AccessDeniedException("connected application"));
            return;
        }
        request.setAttribute(LogContext.CLIENT_ID_ATTRIBUTE, client);
        try (LogContext.Scope _ = LogContext.with(LogContext.CLIENT_ID, client)) {
            chain.doFilter(request, response);
        }
    }

    /** Whether a connected application's token may reach this path: the public API, nothing else. */
    static boolean permits(String path) {
        return path != null && ApiPaths.isPublicApiPath(path) && !oddlyShaped(path);
    }

    /** The public identifier of the application when the caller proved itself with its token, otherwise null. */
    static String clientOf(Authentication authentication) {
        if (authentication instanceof BearerTokenAuthentication token
                && token.getTokenAttributes().get(TokenAuthentication.CLIENT_ATTRIBUTE) instanceof String client) {
            return client;
        }
        return null;
    }

    private static boolean oddlyShaped(String path) {
        if (path.indexOf('%') >= 0 || path.indexOf(';') >= 0 || path.indexOf('\\') >= 0 || path.contains("//")) {
            return true;
        }
        for (String segment : path.split("/")) {
            if (segment.equals(".") || segment.equals("..")) {
                return true;
            }
        }
        return false;
    }
}
