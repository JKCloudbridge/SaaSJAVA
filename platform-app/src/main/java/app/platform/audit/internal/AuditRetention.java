package app.platform.audit.internal;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Removes audit records that are older than the retention period (ADR-0055). The only way records are ever deleted is
 * the
 * database function {@code platform_audit_purge}: the table refuses every other delete, even from the owner, and the
 * function refuses a cut-off younger than 30 days and writes a record of its own that says how many it removed. This
 * job only decides the cut-off and keeps calling the function until a step removes less than a full batch. There is no
 * general scheduler before Sprint 22, so the job runs on a timer of its own on every instance that has it switched on;
 * two instances at once are harmless (the function skips rows another one is removing).
 */
@Component
class AuditRetention implements SmartLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(AuditRetention.class);
    private static final int MOST_STEPS_PER_RUN = 1000;

    private final JdbcClient jdbc;
    private final Clock clock;
    private final AuditProperties.Retention settings;
    private ScheduledExecutorService executor;

    AuditRetention(JdbcClient jdbc, Clock clock, AuditProperties properties) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.settings = properties.retention();
    }

    /**
     * One pass.
     *
     * @return how many records were removed
     */
    long runOnce() {
        Instant cutoff = clock.instant().minus(settings.keep());
        long total = 0;
        for (int step = 0; step < MOST_STEPS_PER_RUN; step++) {
            long removed = jdbc.sql("select platform_audit_purge(:cutoff, :batch)")
                    .param("cutoff", Timestamp.from(cutoff))
                    .param("batch", settings.batchSize())
                    .query(Long.class).single();
            total += removed;
            if (removed < settings.batchSize()) {
                break;
            }
        }
        if (total > 0) {
            LOG.info("Removed {} audit records older than the retention period", total);
        }
        return total;
    }

    @Override
    public void start() {
        if (!settings.enabled() || executor != null) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> new Thread(runnable, "audit-retention"));
        executor.scheduleWithFixedDelay(() -> {
            try {
                runOnce();
            } catch (RuntimeException e) {
                LOG.warn("Audit retention failed ({})", e.getClass().getSimpleName());
            }
        }, settings.interval().toMillis(), settings.interval().toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public void stop() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    @Override
    public boolean isRunning() {
        return executor != null;
    }
}
