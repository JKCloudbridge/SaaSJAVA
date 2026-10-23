package app.platform.sheets.internal;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the archive of old fill-ins (FF2, ADR-0193).
 *
 * @param enabled whether this instance runs the archive job (the default is on; two instances running it is safe, the
 *        second finds nothing the first has not already moved or skips what the first holds)
 * @param age how long after its approval a fill-in's cells stay in the database before the job moves them to the
 *        object store (default 365 days, never less than 30: a fill-in that was just approved is still being looked
 *        at, and the database refuses a correction of an archived one)
 * @param interval how often the job looks (default 10 minutes)
 * @param batchSize how many fill-ins one pass moves in one organization (default 50), so an organization with a long
 *        backlog is worked off over several passes and cannot hold the others up
 */
@ConfigurationProperties("platform.sheets.archive")
record FillArchiveProperties(@DefaultValue("true") boolean enabled, @DefaultValue("365d") Duration age,
        @DefaultValue("10m") Duration interval, @DefaultValue("50") int batchSize) {

    FillArchiveProperties {
        if (age.compareTo(Duration.ofDays(30)) < 0) {
            throw new IllegalArgumentException("platform.sheets.archive.age must be at least 30 days");
        }
        if (interval.compareTo(Duration.ofSeconds(1)) < 0) {
            throw new IllegalArgumentException("platform.sheets.archive.interval must be at least one second");
        }
        if (batchSize < 1 || batchSize > 1_000) {
            throw new IllegalArgumentException("platform.sheets.archive.batch-size must be 1 to 1000");
        }
    }
}
