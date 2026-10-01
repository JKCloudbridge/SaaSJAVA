package app.platform.tenant.internal;

import app.platform.sharedkernel.logging.LogContext;
import app.platform.tenant.TenantContext;
import app.platform.tenant.TenantContexts;
import app.platformapi.ApiPaths;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Establishes the tenant context of every API request from its host name (ADR-0017), for the whole request.
 *
 * <p>The tenant comes from the host and from nowhere else: no header, parameter, path segment or body can name or
 * change it. An unknown organization is answered {@code NOT_FOUND}, a closed one {@code TENANT_UNAVAILABLE}, both in
 * the platform's error model; a platform host passes through without a tenant.
 *
 * <p>Runs just inside the request correlation filter, so refusals carry the request ID. The tenant ID is also left on
 * the request as an attribute, so that the access record written by the outer filter still names it.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
class TenantResolutionFilter extends OncePerRequestFilter {

    private static final String FORWARDED_HOST = "X-Forwarded-Host";

    private final TenantHostResolver resolver;
    private final TenantContexts contexts;
    private final HandlerExceptionResolver errors;
    private final boolean trustForwardedHost;

    TenantResolutionFilter(TenantHostResolver resolver, TenancyProperties properties, TenantContexts contexts,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver errors) {
        this.resolver = resolver;
        this.contexts = contexts;
        this.errors = errors;
        this.trustForwardedHost = properties.trustForwardedHost();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(ApiPaths.V1 + "/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        switch (resolver.resolve(hostOf(request))) {
            case TenantHostResolver.NoTenant _ -> chain.doFilter(request, response);
            case TenantHostResolver.Refused refused -> refuse(request, response, refused);
            case TenantHostResolver.Resolved resolved -> {
                TenantContext context = TenantContext.of(resolved.tenant().id());
                request.setAttribute(LogContext.TENANT_ID_ATTRIBUTE, resolved.tenant().id().toString());
                try (TenantContexts.Scope _ = contexts.open(context)) {
                    chain.doFilter(request, response);
                }
            }
        }
    }

    private String hostOf(HttpServletRequest request) {
        if (trustForwardedHost) {
            String forwarded = request.getHeader(FORWARDED_HOST);
            if (forwarded != null && !forwarded.isBlank()) {
                // A chain of proxies appends; the first value is the one the client addressed.
                int comma = forwarded.indexOf(',');
                return comma >= 0 ? forwarded.substring(0, comma) : forwarded;
            }
        }
        return request.getServerName();
    }

    /** Answers through the platform's one error handler, so the body is the same model as everywhere. */
    private void refuse(HttpServletRequest request, HttpServletResponse response,
            TenantHostResolver.Refused refused) throws IOException {
        if (errors.resolveException(request, response, null, refused.error()) == null) {
            response.sendError(refused.error().code().httpStatus());
        }
    }
}
