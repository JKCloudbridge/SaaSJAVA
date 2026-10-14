package app.platform.data.internal;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the CSV imports (Sprint 17, ADR-0123).
 *
 * @param enabled whether this instance runs import jobs (two instances share them safely: a job is claimed with a
 *        lease)
 * @param maxRows the most data rows a file can have (default 100000)
 * @param maxFileBytes the largest file in bytes (default 20 MiB)
 * @param batchRows how many rows one transaction handles and then records its progress (default 100)
 * @param dryRunMaxRows the most rows a dry run reads: it is one transaction that is rolled back (default 10000)
 * @param maxErrors the most failed rows a job records; the count goes on (default 10000)
 * @param maxActivePerOrganization the most imports of an organization that wait or run at the same time (default 3)
 * @param retention how long the file and the report of failed rows are kept after the import ended (default 7 days)
 * @param lease how long a claim of a job lasts without a sign of life (default 2 minutes)
 * @param sweepInterval how often the sweep over all organizations looks for jobs to carry on (default 2 minutes); a
 *        file that is uploaded starts its job at once
 */
@ConfigurationProperties("platform.data.imports")
record ImportProperties(@DefaultValue("true") boolean enabled, @DefaultValue("100000") int maxRows,
        @DefaultValue("20971520") long maxFileBytes, @DefaultValue("100") int batchRows,
        @DefaultValue("10000") int dryRunMaxRows, @DefaultValue("10000") int maxErrors,
        @DefaultValue("3") int maxActivePerOrganization, @DefaultValue("7d") Duration retention,
        @DefaultValue("2m") Duration lease, @DefaultValue("2m") Duration sweepInterval) {

    ImportProperties {
        if (maxRows < 1 || maxRows > 1_000_000 || maxFileBytes < 1024 || maxFileBytes > 1_073_741_824L
                || batchRows < 1 || batchRows > 1000 || dryRunMaxRows < 1 || dryRunMaxRows > 100_000
                || maxErrors < 1 || maxErrors > 100_000 || maxActivePerOrganization < 1
                || maxActivePerOrganization > 100 || retention.compareTo(Duration.ofSeconds(1)) < 0
                || lease.compareTo(Duration.ofSeconds(5)) < 0 || sweepInterval.compareTo(Duration.ofSeconds(1)) < 0) {
            throw new IllegalArgumentException("platform.data.imports: a setting is outside its range");
        }
    }
}
