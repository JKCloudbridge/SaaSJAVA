package app.platform.audit.internal;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the audit module (ADR-0055).
 *
 * @param retention how long audit records are kept and when old ones are removed
 */
@ConfigurationProperties("platform.audit")
record AuditProperties(@DefaultValue Retention retention) {

    /**
     * Retention of audit records.
     *
     * @param enabled whether this instance runs the retention job
     * @param interval how often the job runs
     * @param keep how long a record is kept (default 400 days, a little over a year, so a yearly review still finds
     *        everything); never less than 31 days, because the database refuses to remove anything younger than 30
     * @param batchSize the most records one removal step deletes (1 to 10000)
     */
    record Retention(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("1d") Duration interval,
            @DefaultValue("400d") Duration keep,
            @DefaultValue("1000") int batchSize) {

        Retention {
            if (keep.compareTo(Duration.ofDays(31)) < 0) {
                throw new IllegalArgumentException("platform.audit.retention.keep must be at least 31 days");
            }
            if (batchSize < 1 || batchSize > 10_000) {
                throw new IllegalArgumentException("platform.audit.retention.batch-size must be 1 to 10000");
            }
        }
    }
}
