package app.platform.data.internal;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the record store (Sprints 14 and 16).
 *
 * @param limits how large a record may be
 * @param retention how long the recycle bin keeps a record and how it is emptied (ADR-0086)
 * @param index how the typed copies of indexed fields are filled and emptied (ADR-0101)
 * @param query the bounds of a query (ADR-0100, ADR-0105)
 * @param links the bounds of what removing a record does to the records that point at it (ADR-0106)
 * @param recalculation how the stored results of edited formulas are brought up to date (ADR-0113)
 */
@ConfigurationProperties("platform.data")
record RecordProperties(@DefaultValue Limits limits, @DefaultValue Retention retention, @DefaultValue Index index,
        @DefaultValue Query query, @DefaultValue Links links, @DefaultValue Recalculation recalculation) {

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

    /**
     * The index policy: filling the typed copy of a newly indexed field for the records that exist, and emptying it
     * when a field stops being indexed.
     *
     * @param enabled whether this instance does the work (two instances sharing it is safe: each batch is taken under
     *        a lock and is repeatable)
     * @param batchSize how many records one batch looks at (default 1000)
     * @param batchesPerPass the most batches one pass runs per field before it gives the instance back (default 100)
     * @param interval how often the sweep over all organizations runs (default 5 minutes); a published change starts
     *        the work at once through its event
     */
    record Index(@DefaultValue("true") boolean enabled, @DefaultValue("1000") int batchSize,
            @DefaultValue("100") int batchesPerPass, @DefaultValue("5m") Duration interval) {

        Index {
            if (batchSize < 1 || batchSize > 10_000 || batchesPerPass < 1 || batchesPerPass > 100_000
                    || interval.compareTo(Duration.ofSeconds(1)) < 0) {
                throw new IllegalArgumentException("platform.data.index: a batch size of 1 to 10000, 1 to 100000 "
                        + "batches per pass and an interval of at least one second");
            }
        }
    }

    /**
     * The bounds of a query. A query that goes beyond one of them is refused with the bound named.
     *
     * @param defaultPageSize the page size when the request names none (default 50)
     * @param maxPageSize the most records one page holds (default 200)
     * @param maxFilterDepth how deep the filter tree may be (default 4)
     * @param maxFilterNodes how many conditions and groups a filter may have in all (default 50)
     * @param maxInValues how many values one "in" condition may list (default 100)
     * @param maxFields how many fields a projection or one lookup resolution may name (default 100)
     * @param unindexedScanLimit the most live records an object may have for a filter or sort on a field without a
     *        complete index to be allowed (default 20000; ADR-0105)
     */
    record Query(@DefaultValue("50") int defaultPageSize, @DefaultValue("200") int maxPageSize,
            @DefaultValue("4") int maxFilterDepth, @DefaultValue("50") int maxFilterNodes,
            @DefaultValue("100") int maxInValues, @DefaultValue("100") int maxFields,
            @DefaultValue("20000") int unindexedScanLimit) {

        Query {
            if (defaultPageSize < 1 || maxPageSize < defaultPageSize || maxPageSize > 1000 || maxFilterDepth < 1
                    || maxFilterDepth > 10 || maxFilterNodes < 1 || maxFilterNodes > 500 || maxInValues < 1
                    || maxInValues > 1000 || maxFields < 1 || maxFields > 500 || unindexedScanLimit < 0) {
                throw new IllegalArgumentException("platform.data.query: a setting is outside its range");
            }
        }
    }

    /**
     * What removing a record does to the records that point at it.
     *
     * @param maxAffected the most records one removal may change or remove together with the record (default 10000);
     *        a removal that would touch more is refused with the count
     * @param backfillBatchSize how many records one batch of the link index backfill looks at (default 1000)
     */
    record Links(@DefaultValue("10000") int maxAffected, @DefaultValue("1000") int backfillBatchSize) {

        Links {
            if (maxAffected < 1 || maxAffected > 100_000 || backfillBatchSize < 1 || backfillBatchSize > 10_000) {
                throw new IllegalArgumentException("platform.data.links: a setting is outside its range");
            }
        }
    }

    /**
     * The recalculation of stored formula results after a formula was edited (Sprint 16A, ADR-0113).
     *
     * @param enabled whether this instance does the work (two instances sharing it is safe: each batch is taken under a
     *        lock and is repeatable)
     * @param batchSize how many records one batch looks at, and rewrites in one transaction (default 500)
     * @param batchesPerPass the most batches one pass runs per object before it gives the instance back (default 100)
     * @param interval how often the sweep over all organizations runs (default 5 minutes); a published change starts
     *        the work at once through its event, and the sweep also finds an object whose formulas changed without one
     * @param closingDelay how long after the first sweep the records changed meanwhile are looked at again: longer than
     *        the longest write can take, so that a write that began before the change and ended after it is found
     *        (default 30 seconds, the time limit of a write)
     */
    record Recalculation(@DefaultValue("true") boolean enabled, @DefaultValue("500") int batchSize,
            @DefaultValue("100") int batchesPerPass, @DefaultValue("5m") Duration interval,
            @DefaultValue("30s") Duration closingDelay) {

        Recalculation {
            if (batchSize < 1 || batchSize > 5_000 || batchesPerPass < 1 || batchesPerPass > 100_000
                    || interval.compareTo(Duration.ofSeconds(1)) < 0 || closingDelay.isNegative()) {
                throw new IllegalArgumentException("platform.data.recalculation: a batch size of 1 to 5000, 1 to "
                        + "100000 batches per pass, an interval of at least one second and a delay that is not "
                        + "negative");
            }
        }
    }
}
