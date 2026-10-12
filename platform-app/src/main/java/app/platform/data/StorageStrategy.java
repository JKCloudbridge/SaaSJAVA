package app.platform.data;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Where and how records are kept (Sprint 14, ADR-0088). The generic JSON store ({@code object_record}) is the one
 * implementation; a store with typed columns or a relational table for a high-value object can be added behind this
 * interface without the rest of the platform noticing, which is the fallback the benchmark gate (ADR-0082) named.
 *
 * <p>This is the <strong>storage</strong> contract, below the rules: it decides nothing about permissions, field names,
 * values, events or audit (those are {@link Records} and the gate). It trusts that the object API name and the field
 * names it is given came from the metadata catalogue, and still never builds SQL from them: names are passed as
 * parameters. Every method answers for the organization of the thread's tenant context, runs in the caller's
 * transaction and filters by that organization itself in addition to the database's row level security (ADR-0003).
 */
public interface StorageStrategy {

    /**
     * A record that was removed for good.
     *
     * @param objectApiName its object
     * @param id its identifier
     */
    record Purged(String objectApiName, UUID id) {
    }

    /**
     * Stores a new record; the identifier is made by the storage.
     *
     * @param ownerId the owner
     * @param actor who creates it
     * @param fields the values (no null values)
     */
    DataRecord insert(String objectApiName, UUID ownerId, UUID actor, Map<String, Object> fields);

    /** The record of the object with the identifier, live or in the recycle bin, or null when there is none. */
    DataRecord find(String objectApiName, UUID id);

    /**
     * Changes the values of a live record that has the expected version.
     *
     * @param set values to set
     * @param clear fields that lose their value
     * @return the changed record, or null when no live record of the object has that identifier and version (the
     *         caller finds out why with {@link #find})
     */
    DataRecord update(String objectApiName, UUID id, long expectedVersion, Map<String, Object> set,
            List<String> clear, UUID actor);

    /** Moves a live record to the recycle bin; null when there is no live record with the identifier. */
    DataRecord softDelete(String objectApiName, UUID id, UUID actor);

    /** Takes a record out of the recycle bin; null when no record in the recycle bin has the identifier. */
    DataRecord restore(String objectApiName, UUID id, UUID actor);

    /** Removes a record in the recycle bin for good; false when no record in the recycle bin has the identifier. */
    boolean purge(String objectApiName, UUID id);

    /** A page of live records in identifier order, at most {@code limit} of them. */
    List<DataRecord> list(String objectApiName, UUID after, int limit);

    /** A page of records in the recycle bin in identifier order, as {@link #list}. */
    List<DataRecord> listDeleted(String objectApiName, UUID after, int limit);

    /** Whether the organization holds at least one record of the object, live or in the recycle bin. */
    boolean hasRecords(String objectApiName);

    /** Whether at least one record of the object, live or in the recycle bin, holds a value in the field. */
    boolean hasValues(String objectApiName, String fieldApiName);

    /**
     * Removes, for the organization of the context, records that have been in the recycle bin for longer than the
     * period, at most {@code limit} of them.
     *
     * @return what was removed
     */
    List<Purged> purgeDeletedOlderThan(Duration period, int limit);
}
