package app.platform.workflow.internal;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the workflow runtime (ADR-0135, ADR-0137). The defaults suit a deployment; tests shorten the times.
 *
 * @param enabled whether this instance runs the dispatcher (every instance may; they coordinate through the database)
 * @param worker how queued executions are claimed and run
 * @param scheduler how due schedules are claimed and fired
 * @param retention how long finished executions are kept
 */
@ConfigurationProperties("platform.workflow")
record WorkflowProperties(@DefaultValue("true") boolean enabled, @DefaultValue Worker worker,
        @DefaultValue Scheduler scheduler, @DefaultValue Retention retention) {

    /**
     * The worker.
     *
     * @param threads how many claim-and-run loops this instance runs (default 2)
     * @param pollInterval pause between two polls when the last poll found nothing (default 1 second)
     * @param batchSize executions claimed per poll (default 10)
     * @param lease how long a claimed execution belongs to the claiming instance before another may take it over; the
     *        runner renews it before every step, so it must exceed the longest single step (default 2 minutes)
     * @param maxAttempts how many times an execution may be claimed before it is set aside as failed without being run
     *        again; a claim is counted when it is made, so a run that keeps killing the process ends (default 3)
     */
    record Worker(@DefaultValue("2") int threads, @DefaultValue("1s") Duration pollInterval,
            @DefaultValue("10") int batchSize, @DefaultValue("2m") Duration lease,
            @DefaultValue("3") int maxAttempts) {

        Worker {
            if (threads < 1 || threads > 16 || batchSize < 1 || batchSize > 200 || maxAttempts < 1
                    || pollInterval.isNegative() || pollInterval.isZero() || lease.isNegative() || lease.isZero()) {
                throw new IllegalArgumentException("platform.workflow.worker.* is out of range");
            }
        }
    }

    /**
     * The scheduler.
     *
     * @param pollInterval pause between two polls for due schedules (default 15 seconds)
     * @param lease how long a claimed schedule belongs to the claiming instance; renewed after every page of records
     *        (default 5 minutes)
     * @param pageSize records read and turned into executions per transaction (default 500)
     * @param maxRecordsPerFiring the most executions one firing of one schedule may create (default 100,000); a
     *        firing that reaches it stops, is recorded as failed and the next firing starts afresh
     * @param maxAttempts how often a failing firing is tried again before it is skipped (default 5)
     */
    record Scheduler(@DefaultValue("15s") Duration pollInterval, @DefaultValue("5m") Duration lease,
            @DefaultValue("500") int pageSize, @DefaultValue("100000") int maxRecordsPerFiring,
            @DefaultValue("5") int maxAttempts) {

        Scheduler {
            if (pollInterval.isNegative() || pollInterval.isZero() || lease.isNegative() || lease.isZero()
                    || pageSize < 1 || pageSize > 5000 || maxRecordsPerFiring < 1 || maxAttempts < 1) {
                throw new IllegalArgumentException("platform.workflow.scheduler.* is out of range");
            }
        }
    }

    /**
     * Retention of finished executions.
     *
     * @param executions how long a finished execution and its steps stay (default 90 days, never fewer than 30, like
     *        the audit records)
     * @param interval pause between two clean-ups (default 1 hour)
     * @param batchSize executions removed per organization and pass (default 1000)
     */
    record Retention(@DefaultValue("90d") Duration executions, @DefaultValue("1h") Duration interval,
            @DefaultValue("1000") int batchSize) {

        Retention {
            if (executions.compareTo(Duration.ofDays(30)) < 0) {
                throw new IllegalArgumentException("platform.workflow.retention.executions must be at least 30 days");
            }
            if (interval.isNegative() || interval.isZero() || batchSize < 1) {
                throw new IllegalArgumentException("platform.workflow.retention.* is out of range");
            }
        }
    }
}
