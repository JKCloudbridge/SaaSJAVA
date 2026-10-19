package app.platform.platformadmin.internal;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the end of a trial (Sprint 25, ADR-0162).
 *
 * @param enabled whether this instance runs the job (every instance may; they coordinate through the database)
 * @param grace how long the organization keeps working after the trial ends (default 7 days)
 * @param firstNoticeDays how many days before the suspension the first warning is sent (default 7)
 * @param finalNoticeDays how many days before the suspension the last warning is sent (default 1)
 * @param interval how often the job looks (default 10 minutes)
 * @param batchSize how many trials one look handles (default 50)
 */
@ConfigurationProperties("platform.licensing.trial")
record TrialEndProperties(@DefaultValue("true") boolean enabled, @DefaultValue("7d") Duration grace,
        @DefaultValue("7") int firstNoticeDays, @DefaultValue("1") int finalNoticeDays,
        @DefaultValue("10m") Duration interval, @DefaultValue("50") int batchSize) {

    TrialEndProperties {
        if (grace.isNegative() || grace.compareTo(Duration.ofDays(365)) > 0 || firstNoticeDays < 1
                || finalNoticeDays < 1 || finalNoticeDays > firstNoticeDays
                || interval.compareTo(Duration.ofSeconds(1)) < 0 || batchSize < 1 || batchSize > 500) {
            throw new IllegalArgumentException("platform.licensing.trial.* is out of range");
        }
    }

    TrialEndPolicy.Settings policy() {
        return new TrialEndPolicy.Settings(grace, firstNoticeDays, finalNoticeDays);
    }
}
