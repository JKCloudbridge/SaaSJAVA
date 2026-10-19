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
 * @param retention how long finished executions and dead letters are kept
 * @param retry how a failed attempt of an action is tried again (ADR-0142)
 * @param limits the guards against loops and floods (ADR-0140) and the mail an organization's workflows may queue
 */
@ConfigurationProperties("platform.workflow")
record WorkflowProperties(@DefaultValue("true") boolean enabled, @DefaultValue Worker worker,
        @DefaultValue Scheduler scheduler, @DefaultValue Retention retention, @DefaultValue Retry retry,
        @DefaultValue Limits limits) {

    /**
     * How a failed attempt is tried again (ADR-0142).
     *
     * @param maxAttempts the most attempts of one step, the first included (default 5); a definition may lower it
     * @param initialDelay the wait after the first failed attempt (default 30 seconds)
     * @param maxDelay the longest wait between two attempts (default 1 hour)
     * @param multiplier how much longer each wait is than the one before (default 2)
     * @param jitter the share of the wait that is added at random, from 0 up (default 0.2); never negative, so a wait
     *        is never shorter than the exponential term
     */
    record Retry(@DefaultValue("5") int maxAttempts, @DefaultValue("30s") Duration initialDelay,
            @DefaultValue("1h") Duration maxDelay, @DefaultValue("2") double multiplier,
            @DefaultValue("0.2") double jitter) {

        Retry {
            if (maxAttempts < 1 || maxAttempts > 100 || initialDelay.isNegative() || initialDelay.isZero()
                    || maxDelay.compareTo(initialDelay) < 0 || jitter < 0 || jitter > 0.5 || multiplier > 10) {
                throw new IllegalArgumentException("platform.workflow.retry.* is out of range");
            }
            if (multiplier < 1 + jitter) {
                // The waits must not shrink from one attempt to the next (ADR-0142).
                throw new IllegalArgumentException("platform.workflow.retry.multiplier must be at least 1 + jitter");
            }
        }
    }

    /**
     * Guards against loops and floods (ADR-0140).
     *
     * @param maxChainDepth how many workflow executions may follow one another from one change (default 5)
     * @param maxExecutionsPerMinute how many executions one object of one organization may start per minute before the
     *        rest are delayed, not dropped (default 600)
     * @param mailsPerOrganizationPerHour how many mails the workflows of one organization may queue per hour (default
     *        500); past it a step waits for its next attempt
     */
    record Limits(@DefaultValue("5") int maxChainDepth, @DefaultValue("600") int maxExecutionsPerMinute,
            @DefaultValue("500") int mailsPerOrganizationPerHour) {

        Limits {
            if (maxChainDepth < 1 || maxChainDepth > 50 || maxExecutionsPerMinute < 1
                    || mailsPerOrganizationPerHour < 1) {
                throw new IllegalArgumentException("platform.workflow.limits.* is out of range");
            }
        }
    }

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
     * @param perOrganization the most executions of one organization that one claim takes (default 3): the queue is
     *        shared by turns between the organizations that have work, so one organization with a long queue cannot
     *        keep the others waiting (Sprint 25, ADR-0164)
     */
    record Worker(@DefaultValue("2") int threads, @DefaultValue("1s") Duration pollInterval,
            @DefaultValue("10") int batchSize, @DefaultValue("2m") Duration lease,
            @DefaultValue("3") int maxAttempts, @DefaultValue("3") int perOrganization) {

        Worker {
            if (threads < 1 || threads > 16 || batchSize < 1 || batchSize > 200 || maxAttempts < 1
                    || perOrganization < 1 || perOrganization > batchSize
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
     * @param deadLetter how long a dead-lettered execution stays, longer than a completed one so a person has time to
     *        look (default 180 days, never fewer than 30)
     */
    record Retention(@DefaultValue("90d") Duration executions, @DefaultValue("1h") Duration interval,
            @DefaultValue("1000") int batchSize, @DefaultValue("180d") Duration deadLetter) {

        Retention {
            if (executions.compareTo(Duration.ofDays(30)) < 0) {
                throw new IllegalArgumentException("platform.workflow.retention.executions must be at least 30 days");
            }
            if (deadLetter.compareTo(Duration.ofDays(30)) < 0) {
                throw new IllegalArgumentException("platform.workflow.retention.dead-letter must be at least 30 days");
            }
            if (interval.isNegative() || interval.isZero() || batchSize < 1) {
                throw new IllegalArgumentException("platform.workflow.retention.* is out of range");
            }
        }
    }
}
