package app.platform.observability.correlation;

import app.platform.sharedkernel.logging.LogContext;
import app.platformapi.ApiHeaders;
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
        event.addKeyValue("http_method", request.getMethod())
                .addKeyValue("http_path", abbreviate(request.getRequestURI()))
                .addKeyValue("http_status", status)
                .addKeyValue("duration_ms", (System.nanoTime() - startedAt) / 1_000_000)
                .log("request completed");
    }

    private static String abbreviate(String path) {
        return path.length() > MAX_LOGGED_PATH ? path.substring(0, MAX_LOGGED_PATH) : path;
    }
}
