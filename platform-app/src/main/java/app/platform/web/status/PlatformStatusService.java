package app.platform.web.status;

import app.platformapi.PlatformStatus;
import java.time.Clock;
import java.time.OffsetDateTime;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Answers the status question by running one query. It runs in a transaction on purpose: that is the path every
 * later database access takes, so the trace, the log and the database connection carry the request identifiers
 * exactly as real work will (ADR-0012).
 */
@Service
class PlatformStatusService {

    static final String SERVICE_NAME = "platform";
    static final String API_VERSION = "v1";

    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;
    private final Clock clock;

    PlatformStatusService(JdbcClient jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setReadOnly(true);
        this.clock = Clock.systemUTC();
    }

    PlatformStatus status() {
        OffsetDateTime databaseTime = transaction.execute(
                status -> jdbc.sql("select now()").query(OffsetDateTime.class).single());
        return new PlatformStatus(SERVICE_NAME, API_VERSION, clock.instant(), databaseTime.toInstant());
    }
}
