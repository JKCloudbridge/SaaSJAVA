package app.platform.observability;

import app.platform.sharedkernel.logging.LogContext;
import java.util.Objects;
import java.util.Optional;

/**
 * One unexpected error together with the correlation identifiers of the work that failed.
 *
 * @param error the failure, never null
 * @param requestId the request ID, or {@value #UNKNOWN_REQUEST} outside a request
 * @param traceId the distributed trace ID, when tracing is active
 * @param tenantId the tenant, when the work ran for one (not populated before Sprint 2)
 * @param method the HTTP method of the request, empty outside a request
 * @param path the request path without its query string, empty outside a request
 */
public record ErrorReport(
        Throwable error,
        String requestId,
        Optional<String> traceId,
        Optional<String> tenantId,
        String method,
        String path) {

    /** Request ID used when the failure happened outside any request. */
    public static final String UNKNOWN_REQUEST = "unknown";

    public ErrorReport {
        Objects.requireNonNull(error, "error");
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(traceId, "traceId");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(path, "path");
    }

    /** A report for the work running on the current thread, filled from the logging context. */
    public static ErrorReport current(Throwable error, String method, String path) {
        return new ErrorReport(
                error,
                LogContext.requestId().orElse(UNKNOWN_REQUEST),
                LogContext.traceId(),
                LogContext.tenantId(),
                method,
                path);
    }
}
