package app.platform.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ErrorTrackerTest {

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    @Test
    void everyReporterReceivesTheReport() {
        List<ErrorReport> first = new ArrayList<>();
        List<ErrorReport> second = new ArrayList<>();
        ErrorTracker tracker = new ErrorTracker(List.of(first::add, second::add), meters);
        ErrorReport report = report(new IllegalStateException("boom"));

        tracker.track(report);

        assertThat(first).containsExactly(report);
        assertThat(second).containsExactly(report);
    }

    @Test
    void aFailingReporterNeverStopsTheOthersOrTheCaller() {
        List<ErrorReport> received = new ArrayList<>();
        ErrorReporter broken = failing -> {
            throw new IllegalStateException("reporter is down");
        };
        ErrorTracker tracker = new ErrorTracker(List.of(broken, received::add), meters);

        tracker.track(report(new IllegalStateException("boom")));

        assertThat(received).hasSize(1);
    }

    @Test
    void countsUnexpectedErrorsByExceptionType() {
        ErrorTracker tracker = new ErrorTracker(List.of(), meters);

        tracker.track(report(new IllegalStateException("one")));
        tracker.track(report(new IllegalStateException("two")));
        tracker.track(report(new IllegalArgumentException("three")));

        assertThat(meters.get("platform.errors.unhandled").tag("exception", "IllegalStateException").counter().count())
                .isEqualTo(2.0);
        assertThat(meters.get("platform.errors.unhandled").tag("exception", "IllegalArgumentException").counter()
                .count()).isEqualTo(1.0);
    }

    private static ErrorReport report(Throwable error) {
        return new ErrorReport(error, "req_12345678", Optional.empty(), Optional.empty(), "GET", "/api/v1/x");
    }
}
