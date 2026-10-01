package app.platform.sharedkernel.logging;

import java.util.Optional;
import org.slf4j.MDC;

/**
 * The keys of the per-request logging context and small helpers to read and set them. Every structured log line
 * carries whatever of these is present, so one request can be followed across modules (ADR-0012).
 *
 * <p>{@link #TENANT_ID} is set in one place only, the tenant module, whenever a tenant context is opened (a request,
 * an asynchronous job, an event being handled). Nothing else sets it, and it is never taken from client input.
 */
public final class LogContext {

    /** Identifier of the HTTP request (see the request ID header of the API contract). */
    public static final String REQUEST_ID = "requestId";

    /** Distributed trace identifier, set by the tracing integration. */
    public static final String TRACE_ID = "traceId";

    /** Identifier of the current span, set by the tracing integration. */
    public static final String SPAN_ID = "spanId";

    /** Tenant the current work runs for. */
    public static final String TENANT_ID = "tenantId";

    /**
     * Name of the servlet request attribute that keeps the tenant ID after the tenant filter has finished, so the
     * access record, which is written by an outer filter, still names the tenant.
     */
    public static final String TENANT_ID_ATTRIBUTE = "app.platform.tenantId";

    /**
     * Name of the servlet request attribute that keeps the request ID after the correlation filter has finished,
     * for the container's error dispatch (where the logging context is already gone).
     */
    public static final String REQUEST_ID_ATTRIBUTE = "app.platform.requestId";

    /** Name of the servlet request attribute that keeps the trace ID for the container's error dispatch. */
    public static final String TRACE_ID_ATTRIBUTE = "app.platform.traceId";

    private LogContext() {
    }

    /** The request ID of the current thread, if a request is being processed. */
    public static Optional<String> requestId() {
        return get(REQUEST_ID);
    }

    /** The trace ID of the current thread, if a trace is active. */
    public static Optional<String> traceId() {
        return get(TRACE_ID);
    }

    /** The tenant ID of the current thread, if one was set. */
    public static Optional<String> tenantId() {
        return get(TENANT_ID);
    }

    /**
     * Sets a context value until the returned scope is closed, then restores the previous value.
     *
     * @param key one of the key constants
     * @param value the value to log
     * @return the scope to close, ideally with try-with-resources
     */
    public static Scope with(String key, String value) {
        String previous = MDC.get(key);
        MDC.put(key, value);
        return () -> restore(key, previous);
    }

    private static Optional<String> get(String key) {
        String value = MDC.get(key);
        return value == null || value.isEmpty() ? Optional.empty() : Optional.of(value);
    }

    private static void restore(String key, String previous) {
        if (previous == null) {
            MDC.remove(key);
        } else {
            MDC.put(key, previous);
        }
    }

    /** A temporary logging-context value; closing restores the previous one. Does not throw. */
    @FunctionalInterface
    public interface Scope extends AutoCloseable {

        @Override
        void close();
    }
}
