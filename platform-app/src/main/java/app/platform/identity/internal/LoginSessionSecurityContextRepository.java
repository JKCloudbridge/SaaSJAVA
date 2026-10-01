package app.platform.identity.internal;

import app.platform.tenant.TenantContext;
import app.platform.tenant.TenantContexts;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.DeferredSecurityContext;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpRequestResponseHolder;
import org.springframework.security.web.context.SecurityContextRepository;

/**
 * Tells the authorization endpoint who has just signed in, from the login cookie (decision of Sprint 3, ADR-0019).
 *
 * <p>There is no server-side HTTP session: the library asks this repository for the security context and gets the user
 * of the live login session behind the cookie, or an empty context. A login session counts only on the host it was made
 * on (an organization, or the platform host), only while it is alive in the database, and only for a user who may still
 * sign in under the same security version. Nothing is written back: login sessions are created by the sign-in
 * endpoint, not by the framework.
 *
 * <p>Used by the authorization endpoint's filter chain only. The API chain never accepts a login cookie as a
 * credential.
 */
final class LoginSessionSecurityContextRepository implements SecurityContextRepository {

    private final LoginSessions sessions;
    private final TenantContexts contexts;

    LoginSessionSecurityContextRepository(LoginSessions sessions, TenantContexts contexts) {
        this.sessions = sessions;
        this.contexts = contexts;
    }

    @Override
    @SuppressWarnings("deprecation") // The interface still requires it; the deferred form below does the work.
    public SecurityContext loadContext(HttpRequestResponseHolder holder) {
        return resolve(holder.getRequest());
    }

    @Override
    public DeferredSecurityContext loadDeferredContext(HttpServletRequest request) {
        return new DeferredSecurityContext() {
            private SecurityContext context;

            @Override
            public SecurityContext get() {
                if (context == null) {
                    context = resolve(request);
                }
                return context;
            }

            @Override
            public boolean isGenerated() {
                return get().getAuthentication() == null;
            }
        };
    }

    @Override
    public void saveContext(SecurityContext context, HttpServletRequest request, HttpServletResponse response) {
        // Intentionally nothing: the sign-in endpoint creates login sessions.
    }

    @Override
    public boolean containsContext(HttpServletRequest request) {
        return AuthCookies.read(request, AuthCookies.LOGIN).isPresent();
    }

    private SecurityContext resolve(HttpServletRequest request) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        Optional<LoginSessions.Alive> alive = AuthCookies.read(request, AuthCookies.LOGIN)
                .flatMap(sessions::findAlive)
                .filter(session -> sameHost(session.boundTenantId()));
        alive.ifPresent(session -> context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                session.userId().toString(), null, List.of())));
        return context;
    }

    private boolean sameHost(UUID boundTenantId) {
        UUID current = contexts.current().map(TenantContext::tenantId).map(tenant -> tenant.value()).orElse(null);
        return Objects.equals(boundTenantId, current);
    }

    /** For tests: a context for a user, built the same way. */
    static SecurityContext contextOf(UUID userId) {
        SecurityContext context = new SecurityContextImpl();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(userId.toString(), null,
                List.of()));
        return context;
    }
}
