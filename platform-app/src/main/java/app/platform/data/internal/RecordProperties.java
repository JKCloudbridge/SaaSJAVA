package app.platform.data.internal;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the record store (Sprint 14).
 *
 * @param limits how large a record may be
 * @param retention how long the recycle bin keeps a record and how it is emptied (ADR-0086)
 */
@ConfigurationProperties("platform.data")
record RecordProperties(@DefaultValue Limits limits, @DefaultValue Retention retention) {

    /**
     * Bounds on what one request may store.
     *
     * @param maxRecordBytes the most a record's values may take as JSON text, in bytes (default 262144; the database
     *        refuses more than 524288 whatever this says)
     */
    record Limits(@DefaultValue("262144") int maxRecordBytes) {

        Limits {
            if (maxRecordBytes < 1024 || maxRecordBytes > 524_288) {
                throw new IllegalArgumentException("platform.data.limits.max-record-bytes must be 1024 to 524288");
            }
        }
    }

    /**
     * The recycle bin.
     *
     * @param enabled whether this instance empties the recycle bin (two instances doing it at once is harmless)
     * @param recycleBin how long a deleted record stays in the recycle bin before it is removed for good (default 30
     *        days, never less than one second)
     * @param interval how often the clean-up runs (default one hour)
     * @param batchSize the most records one pass removes per organization (default 500)
     */
    record Retention(@DefaultValue("true") boolean enabled, @DefaultValue("30d") Duration recycleBin,
            @DefaultValue("1h") Duration interval, @DefaultValue("500") int batchSize) {

        Retention {
            if (recycleBin.compareTo(Duration.ofSeconds(1)) < 0 || interval.compareTo(Duration.ofSeconds(1)) < 0
                    || batchSize < 1 || batchSize > 10_000) {
                throw new IllegalArgumentException(
                        "platform.data.retention: periods of at least one second and a batch size of 1 to 10000");
            }
        }
    }
}
