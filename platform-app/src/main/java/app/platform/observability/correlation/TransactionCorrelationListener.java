package app.platform.observability.correlation;

import app.platform.sharedkernel.logging.LogContext;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Optional;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionExecution;
import org.springframework.transaction.TransactionExecutionListener;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Ties the database to the request: at the start of every transaction the connection's {@code application_name}
 * is set, for that transaction only, to {@code t=<trace id> r=<request id>}. The database server log (when its
 * line prefix includes the application name) and the server's activity view then show which request a slow or
 * failing statement belongs to, which closes the browser to API to database chain of ADR-0012.
 *
 * <p>The setting is transaction-local, so a pooled connection cannot carry one request's identifiers into the
 * next. The cost is one extra round trip per transaction, not per statement. Sprint 2 sets the tenant on the same
 * hook in the same way. PostgreSQL silently cuts application names at 63 characters; the identifiers are sized to
 * fit (a 32-character trace ID and a 26-character request ID).
 */
@Component
class TransactionCorrelationListener implements TransactionExecutionListener {

    static final int MAX_APPLICATION_NAME = 63;
    private static final Logger LOG = LoggerFactory.getLogger(TransactionCorrelationListener.class);

    private final DataSource dataSource;

    TransactionCorrelationListener(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void afterBegin(TransactionExecution transaction, Throwable beginFailure) {
        if (beginFailure != null) {
            return;
        }
        Optional<String> name = applicationName(LogContext.traceId(), LogContext.requestId());
        Object resource = TransactionSynchronizationManager.getResource(dataSource);
        if (name.isEmpty() || !(resource instanceof ConnectionHolder holder)) {
            return;
        }
        try (PreparedStatement statement =
                holder.getConnection().prepareStatement("select set_config('application_name', ?, true)")) {
            statement.setString(1, name.get());
            statement.execute();
        } catch (SQLException e) {
            // Correlation is best effort; the cause is not logged because driver messages can quote data.
            LOG.debug("Could not stamp the transaction with the request identifiers, SQL state {}", e.getSQLState());
        }
    }

    /** The application name for the given identifiers, or empty when there is nothing to stamp. */
    static Optional<String> applicationName(Optional<String> traceId, Optional<String> requestId) {
        StringBuilder name = new StringBuilder();
        traceId.ifPresent(id -> name.append("t=").append(id));
        requestId.ifPresent(id -> name.append(name.isEmpty() ? "" : " ").append("r=").append(id));
        if (name.isEmpty()) {
            return Optional.empty();
        }
        String value = name.length() > MAX_APPLICATION_NAME ? name.substring(0, MAX_APPLICATION_NAME) : name.toString();
        return Optional.of(value);
    }
}
