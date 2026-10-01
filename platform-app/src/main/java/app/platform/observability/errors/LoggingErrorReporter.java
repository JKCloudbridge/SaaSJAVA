package app.platform.observability.errors;

import app.platform.observability.ErrorReport;
import app.platform.observability.ErrorReporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.stereotype.Component;

/**
 * The built-in error reporter: one structured ERROR record per unexpected failure, searchable by request ID or
 * trace ID. It logs the summary of the throwable (see {@link ThrowableSummary}), never the throwable itself,
 * so that quoted data in library messages cannot reach the logs.
 */
@Component
class LoggingErrorReporter implements ErrorReporter {

    private static final Logger LOG = LoggerFactory.getLogger("platform.error");

    @Override
    public void report(ErrorReport report) {
        ThrowableSummary summary = ThrowableSummary.of(report.error());
        LoggingEventBuilder event = LOG.atError()
                .addKeyValue("error_type", summary.chain().get(0))
                .addKeyValue("error_chain", String.join(" <- ", summary.chain()))
                .addKeyValue("error_stack", summary.stackTrace())
                .addKeyValue("http_method", report.method())
                .addKeyValue("http_path", report.path());
        if (summary.sqlState().isPresent()) {
            event = event.addKeyValue("error_sql_state", summary.sqlState().get());
        }
        if (summary.message().isPresent()) {
            event = event.addKeyValue("error_message", summary.message().get());
        }
        event.log("Unexpected error while handling request {}", report.requestId());
    }
}
