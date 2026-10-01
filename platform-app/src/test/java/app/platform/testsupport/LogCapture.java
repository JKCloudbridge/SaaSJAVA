package app.platform.testsupport;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.slf4j.LoggerFactory;

/**
 * Collects the log events of one logger, with their key-value pairs and logging context (MDC) as they were when
 * the line was written. Close it (try-with-resources) to detach.
 */
public final class LogCapture implements AutoCloseable {

    private final Logger logger;
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    private LogCapture(String loggerName) {
        this.logger = (Logger) LoggerFactory.getLogger(loggerName);
        this.logger.setLevel(Level.TRACE);
        this.appender.start();
        this.logger.addAppender(appender);
    }

    /** Starts capturing the named logger. */
    public static LogCapture of(String loggerName) {
        return new LogCapture(loggerName);
    }

    /** The events captured so far. */
    public List<ILoggingEvent> events() {
        return List.copyOf(appender.list);
    }

    /** The value of a key-value pair of an event, or null. */
    public static Object keyValue(ILoggingEvent event, String key) {
        if (event.getKeyValuePairs() == null) {
            return null;
        }
        return event.getKeyValuePairs().stream()
                .filter(pair -> pair.key.equals(key))
                .map(pair -> pair.value)
                .findFirst()
                .orElse(null);
    }

    @Override
    public void close() {
        logger.detachAppender(appender);
        appender.stop();
    }
}
