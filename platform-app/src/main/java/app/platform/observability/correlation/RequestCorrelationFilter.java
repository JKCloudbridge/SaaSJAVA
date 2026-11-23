package app.platform.observability.correlation;

import app.platform.sharedkernel.logging.LogContext;
import app.platformapi.ApiHeaders;
import app.platformapi.ApiPaths;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives every request an ID, puts it in the logging context and on the response, tags the current trace span with
 * it and writes one structured access record when the request ends.
 *
 * <p>Runs just inside the tracing filter, so the trace ID is already known here: the response carries both IDs
 * even for failures, and every log line of the request carries both.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
class RequestCorrelationFilter extends OncePerRequestFilter {

    private static final Logger ACCESS = LoggerFactory.getLogger("platform.http");
    private static final int MAX_LOGGED_PATH = 200;

    private final ObjectProvider<Tracer> tracer;

    RequestCorrelationFilter(ObjectProvider<Tracer> tracer) {
        this.tracer = tracer;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = resolveRequestId(request.getHeader(ApiHeaders.REQUEST_ID));
        long startedAt = System.nanoTime();
        LogContext.Scope scope = LogContext.with(LogContext.REQUEST_ID, requestId);
        try {
            // Headers first: they must be set before the response can be committed.
            response.setHeader(ApiHeaders.REQUEST_ID, requestId);
            request.setAttribute(LogContext.REQUEST_ID_ATTRIBUTE, requestId);
            LogContext.traceId().ifPresent(traceId -> {
                response.setHeader(ApiHeaders.TRACE_ID, traceId);
                request.setAttribute(LogContext.TRACE_ID_ATTRIBUTE, traceId);
            });
            tagSpan(requestId);
            try {
                chain.doFilter(request, response);
            } finally {
                logCompletion(request, response, startedAt);
            }
        } finally {
            scope.close();
        }
    }

    private String resolveRequestId(String supplied) {
        return RequestIds.isAcceptable(supplied) ? supplied : RequestIds.generate(System.currentTimeMillis());
    }

    private void tagSpan(String requestId) {
        Tracer current = tracer.getIfAvailable();
        if (current != null) {
            Span span = current.currentSpan();
            if (span != null) {
                span.tag("request.id", requestId);
            }
        }
    }

    private void logCompletion(HttpServletRequest request, HttpServletResponse response, long startedAt) {
        int status = response.getStatus();
        var event = status >= 500 ? ACCESS.atWarn() : ACCESS.atInfo();
        // The tenant filter sits inside this one and has already closed its scope; it leaves the tenant on the
        // request so that this record, the last line of the request, still names it.
        Object tenant = request.getAttribute(LogContext.TENANT_ID_ATTRIBUTE);
        LogContext.Scope tenantScope = tenant instanceof String id
                ? LogContext.with(LogContext.TENANT_ID, id) : () -> { };
        try {
            var line = event.addKeyValue("http_method", request.getMethod())
                    .addKeyValue("http_path", abbreviate(request.getRequestURI()))
                    .addKeyValue("http_status", status)
                    .addKeyValue("duration_ms", (System.nanoTime() - startedAt) / 1_000_000);
            // A request made through a connected application names it (Sprint 31): the public identifier only.
            if (request.getAttribute(LogContext.CLIENT_ID_ATTRIBUTE) instanceof String client) {
                line = line.addKeyValue("client_id", client);
            }
            line.log("request completed");
        } finally {
            tenantScope.close();
        }
    }

    private static String abbreviate(String path) {
        String shown = publicApiRoute(path);
        return shown.length() > MAX_LOGGED_PATH ? shown.substring(0, MAX_LOGGED_PATH) : shown;
    }

    /**
     * A call of the public API logs its route only (for example {@code /api/public/v1/objects}), never what follows:
     * the object, field and record names of a customer are part of the address, and a call that fails before it is
     * routed (a refused token) is logged here too. The status and the request ID say what happened.
     */
    private static String publicApiRoute(String path) {
        if (!ApiPaths.isPublicApiPath(path)) {
            return path;
        }
        String rest = path.substring(ApiPaths.PUBLIC_V1.length());
        int next = rest.indexOf('/', 1);
        return next < 0 ? path : ApiPaths.PUBLIC_V1 + rest.substring(0, next);
    }
}
