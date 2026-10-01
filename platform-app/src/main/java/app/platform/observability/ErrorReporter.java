package app.platform.observability;

/**
 * Hook for sending unexpected errors to an error tracking system. The platform ships one implementation that
 * writes a structured log record; a deployment that wants a hosted error tracking product adds a bean of this type
 * (all reporters are called). The platform itself names no product (ADR-0012).
 *
 * <p>An implementation receives the original throwable. Exception messages can contain personal data (for example
 * a database error that quotes the rejected value), so an implementation that leaves the process must not forward
 * messages without scrubbing them. It must not throw; if it does, the failure is logged and ignored.
 */
@FunctionalInterface
public interface ErrorReporter {

    /** Reports one unexpected error. */
    void report(ErrorReport report);
}
