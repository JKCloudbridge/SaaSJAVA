package app.platform.observability;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Entry point for reporting an unexpected error: counts it and hands it to every {@link ErrorReporter}.
 * A failing reporter never affects the request or the other reporters.
 */
@Component
public class ErrorTracker {

    private static final Logger LOG = LoggerFactory.getLogger(ErrorTracker.class);

    private final List<ErrorReporter> reporters;
    private final MeterRegistry meters;

    /**
     * Creates the tracker.
     *
     * @param reporters every reporter bean, including the built-in logging one
     * @param meters where the error counter is registered
     */
    public ErrorTracker(List<ErrorReporter> reporters, MeterRegistry meters) {
        this.reporters = List.copyOf(reporters);
        this.meters = meters;
    }

    /**
     * Tracks one unexpected error. Expected failures (validation, not found, conflicts) are not tracked; they are
     * normal answers, not incidents.
     */
    public void track(ErrorReport report) {
        meters.counter("platform.errors.unhandled", "exception", report.error().getClass().getSimpleName())
                .increment();
        for (ErrorReporter reporter : reporters) {
            try {
                reporter.report(report);
            } catch (RuntimeException failure) {
                // Deliberately without the throwable: its message could repeat the data we were trying to protect.
                LOG.warn("Error reporter {} failed with {}", reporter.getClass().getSimpleName(),
                        failure.getClass().getName());
            }
        }
    }
}
