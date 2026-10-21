package app.platform.data;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
     * How far one batch of filling a slot got.
     *
     * @param processed how many records the batch looked at
     * @param lastId the identifier of the last record it looked at, or null when there was none
     */
    record Filled(int processed, UUID lastId) {
    }

    /**
     * Stores a new record; the identifier is made by the storage.
     *
     * @param recordType the API name of the record type, or null for a record without one
     * @param ownerId the owner
     * @param actor who creates it
     * @param fields the values (no null values)
     * @param slots the typed copies of the indexed fields, written in the same statement (ADR-0102)
     */
    DataRecord insert(String objectApiName, String recordType, UUID ownerId, UUID actor, Map<String, Object> fields,
            SlotCopy slots);

    /**
     * Stores a new record without typed copies: for code that holds no indexed field (tests of the storage itself).
     * The record rules always use the form with the copies.
     */
    default DataRecord insert(String objectApiName, String recordType, UUID ownerId, UUID actor,
            Map<String, Object> fields) {
        return insert(objectApiName, recordType, ownerId, actor, fields, SlotCopy.none());
    }

    /** The record of the object with the identifier, live or in the recycle bin, or null when there is none. */
    DataRecord find(String objectApiName, UUID id);

    /**
     * Changes the values of a live record that has the expected version.
     *
     * @param recordType the record type the record has afterwards (the same as before when it is not being changed)
     * @param set values to set
     * @param clear fields that lose their value
     * @param slots the typed copies the record has afterwards (every slot not named is emptied)
     * @return the changed record, or null when no live record of the object has that identifier and version (the
     *         caller finds out why with {@link #find})
     */
    DataRecord update(String objectApiName, UUID id, long expectedVersion, String recordType,
            Map<String, Object> set, List<String> clear, UUID actor, SlotCopy slots);

    /** Changes a record without typed copies, as {@link #insert(String, String, UUID, UUID, Map)}. */
    default DataRecord update(String objectApiName, UUID id, long expectedVersion, String recordType,
            Map<String, Object> set, List<String> clear, UUID actor) {
        return update(objectApiName, id, expectedVersion, recordType, set, clear, actor, SlotCopy.none());
    }

    /**
     * Which of the identifiers are live records of the object in the organization. A record in the recycle bin, a
     * record of another object and a record of another organization are not among them, and nothing tells them apart.
     */
    Set<UUID> liveIds(String objectApiName, Collection<UUID> ids);

    /**
     * Takes the row lock of these records of the object, in identifier order, and holds it until the end of the
     * transaction (Sprint 24, ADR-0148): the first step of the record lock guard and of a submit for approval, so that
     * the two are decided one after the other. It changes nothing and does not change the version. Records that do not
     * exist are simply not locked.
     *
     * @return the identifiers whose rows were locked, in identifier order
     */
    List<UUID> lockRows(String objectApiName, Collection<UUID> ids);

    /** Moves a live record to the recycle bin; null when there is no live record with the identifier. */
    default DataRecord softDelete(String objectApiName, UUID id, UUID actor) {
        return softDelete(objectApiName, id, actor, null);
    }

    /**
     * Takes a record out of the recycle bin; null when no record in the recycle bin has the identifier.
     *
     * @param slots the typed copies the record has from now on, computed from its values (a record in the recycle bin
     *        is not indexed, so its copies may be old)
     */
    DataRecord restore(String objectApiName, UUID id, UUID actor, SlotCopy slots);

    /** Takes a record out of the recycle bin without typed copies, as {@code insert}. */
    default DataRecord restore(String objectApiName, UUID id, UUID actor) {
        return restore(objectApiName, id, actor, SlotCopy.none());
    }

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


    // ---- queries (Sprint 16, ADR-0100) ----

    /**
     * One record of the answer to a query, with the sort values it was ordered by (as text, one entry per key of the
     * order, null for a record without a value), which become the cursor of the next page.
     *
     * @param record the record, whole (field permissions are applied above the storage)
     * @param sortValues the values of the sort keys
     */
    record StoredRow(DataRecord record, List<String> sortValues) {

        /** Copies the list (it may hold nulls). */
        public StoredRow {
            sortValues = java.util.Collections.unmodifiableList(new java.util.ArrayList<>(sortValues));
        }
    }

    /**
     * Reads the live records the plan describes, in the order the plan names, at most {@code plan.limit()} of them,
     * after the position the plan names. The statement is built from the plan by the storage and holds no name and no
     * value: they are parameters.
     */
    List<StoredRow> query(QueryPlan plan);

    /** The live records of the object that have these identifiers; others (deleted, of another object) are absent. */
    List<DataRecord> liveRecords(String objectApiName, Collection<UUID> ids);

    // ---- search (Sprint 17, ADR-0124) ----

    /**
     * A word of a search.
     *
     * @param text the word
     * @param beginning whether it matches every word that starts with it
     * @param upperBound for a beginning: a text greater than every word that starts with it
     */
    record SearchWord(String text, boolean beginning, String upperBound) {

        /** Prints the shape only: the word was typed by a person. */
        @Override
        public String toString() {
            return "SearchWord[" + (beginning ? "beginning" : "whole") + "]";
        }
    }

    /**
     * A search over the words of the searchable fields of one object.
     *
     * @param objectApiName the object
     * @param driving the word to read first: the one the fewest records have
     * @param others the other words; a record must have all of them
     * @param fields the searchable fields to look in, the first being the one a match counts most in
     * @param constraint the condition a record must meet to be reached (record-level security), or null
     * @param afterScore where the previous page ended: the score of its last record, or null for the first page
     * @param afterId the identifier of the last record of the previous page
     * @param limit how many records to return
     */
    record SearchPlan(String objectApiName, SearchWord driving, List<SearchWord> others, List<String> fields,
            QueryPlan.Node constraint, Integer afterScore, UUID afterId, int limit) {

        /** Copies the lists. */
        public SearchPlan {
            others = List.copyOf(others);
            fields = List.copyOf(fields);
        }
    }

    /**
     * A record found by a search, with how well it matched.
     *
     * @param record the record, whole (field permissions are applied above the storage)
     * @param score the score: higher is a better match
     */
    record ScoredRecord(DataRecord record, int score) {
    }

    /**
     * The live records the search finds, best first (then by identifier), after the position the plan names. The
     * statement is built by the storage from fixed fragments; the words, the fields and the identifiers are
     * parameters.
     */
    List<ScoredRecord> search(SearchPlan plan);

    // ---- record-level security (Sprint 17, ADR-0115, ADR-0119) ----

    /**
     * The record of the object with the identifier, live or in the recycle bin, when it also meets the condition (the
     * records a member may reach); null when there is no such record or it does not meet the condition, which two
     * cases nothing tells apart.
     */
    DataRecord find(String objectApiName, UUID id, QueryPlan.Node condition);

    /** Whether the live record of the object with the identifier meets the condition. */
    boolean matches(String objectApiName, UUID id, QueryPlan.Node condition);

    /**
     * The live records of the object that have these identifiers and meet the condition; others (deleted, of another
     * object, not meeting it) are absent.
     */
    List<DataRecord> liveRecords(String objectApiName, Collection<UUID> ids, QueryPlan.Node condition);

    /**
     * Which of the identifiers are live records of the object that also meet the condition (Sprint 17A, ADR-0125):
     * the records a member may link to, or may remove together with another. A record that does not meet it, is in
     * the recycle bin, is of another object or of another organization is absent, and nothing tells the cases apart.
     */
    Set<UUID> liveIds(String objectApiName, Collection<UUID> ids, QueryPlan.Node condition);

    /**
     * Which of the identifiers are records of the object, live or in the recycle bin, that meet the condition. A
     * record in the recycle bin keeps its owner and its shares, so the rules of record-level security judge it as
     * they judged it alive (ADR-0125).
     */
    Set<UUID> idsMeeting(String objectApiName, Collection<UUID> ids, QueryPlan.Node condition);

    /**
     * A page of records in the recycle bin that meet the condition, in identifier order, as {@link #listDeleted(String,
     * UUID, int)}.
     */
    List<DataRecord> listDeleted(String objectApiName, UUID after, int limit, QueryPlan.Node condition);

    /**
     * Gives a live record that has the expected version to another owner. The version rises and the time of the change
     * is set, like any change of the record.
     *
     * @return the changed record, or null when no live record of the object has that identifier and version
     */
    DataRecord changeOwner(String objectApiName, UUID id, long expectedVersion, UUID newOwner, UUID actor);


    // ---- links between records (Sprint 16, ADR-0106) ----

    /**
     * A record that was removed together with another one.
     *
     * @param objectApiName its object
     * @param id its identifier
     */
    record Removed(String objectApiName, UUID id) {
    }

    /**
     * Moves a live record to the recycle bin because the record {@code removedWith} was removed (a detail goes with its
     * master); null when there is no live record with the identifier. A person's delete is {@link #softDelete}.
     */
    DataRecord softDelete(String objectApiName, UUID id, UUID actor, UUID removedWith);

    /** The records in the recycle bin that were removed together with the record, at most {@code limit}. */
    List<Removed> removedWith(UUID removedWith, int limit);

    /**
     * Writes the pointers of one reference field for up to {@code limit} live records of the object after the
     * identifier, in identifier order, for records that have none yet. The records are locked while it runs, so a
     * concurrent change cannot leave a pointer that is no longer true.
     *
     * @param targetObject the object the field points at
     */
    Filled backfillLinks(String objectApiName, String fieldApiName, String targetObject, UUID after, int limit,
            UUID actor);

    // ---- the maintenance of the typed copies (the index policy, Sprint 16, ADR-0101) ----

    /**
     * Writes the typed copy of one field into one slot for up to {@code limit} live records of the object after the
     * identifier, in identifier order. The version and the time of last change of a record do not change (ADR-0102).
     *
     * @param slotColumn the name of the slot column (from the index policy, never from a request)
     * @param after the identifier the batch continues after, or null for the first batch
     */
    Filled fillSlot(String objectApiName, String fieldApiName, String slotColumn, UUID after, int limit);

    /**
     * Empties one slot for up to {@code limit} live records of the object that hold a copy in it.
     *
     * @return how many records were emptied
     */
    int clearSlot(String objectApiName, String slotColumn, int limit);

    /**
     * Whether at least one live record of the object has a copy in the slot that differs from what its values give for
     * the field. A full scan of the object: the index policy asks it once, to call a copy complete.
     */
    boolean hasUnfilledSlot(String objectApiName, String fieldApiName, String slotColumn);

    /** Whether at least one live record of the object has a copy in the slot. */
    boolean hasSlotValues(String objectApiName, String slotColumn);

    /**
     * How many live records the object has, counted only up to {@code cap}: the answer is the count when it is below
     * the cap, the cap otherwise. Used to decide whether a scan is allowed (ADR-0105).
     */
    int liveCountUpTo(String objectApiName, int cap);

    // ---- dependent picklists (FA2A, ADR-0177) ----

    /**
     * How many live records of the object hold a value in the dependent field that the rule does not allow for the
     * value
     * they hold in the controlling field (a record without a controlling value holds none that is allowed). A full scan
     * of the object: only a change that makes a field dependent, or changes its pairs, asks it, to tell the
     * administrator how many records the change leaves with an old pair.
     *
     * @param allowed for each value of the controlling field, the values of the dependent field that go with it
     */
    long recordsOutsidePairs(String objectApiName, String dependentField, String controllingField,
            Map<String, List<String>> allowed);

    // ---- uniqueness (Sprint 16A, ADR-0111) ----

    /**
     * How many live records of the object hold, in the field, a value that another live record holds too: the number
     * of records in groups of two or more. Read from the stored values, so it works before the field has a complete
     * copy. A full scan of the object: only a change that makes a field unique asks it.
     *
     * @param slotColumn any slot of the kind of the field; it tells the storage how to compare values (a number as a
     *        number, never as text), and nothing else
     */
    long duplicateRecords(String objectApiName, String fieldApiName, String slotColumn);

    /**
     * Whether a live record of the object other than {@code except} already holds these copies in the constraint.
     *
     * @param constraint the number of the constraint (the bit of {@link SlotCopy#uniqueMask})
     * @param values the copy of each column of the constraint, as text, in the order of its columns (one value, or
     *        the two parents of a junction)
     * @param except the record being written (it may keep its own value), or null for a record that does not exist yet
     */
    boolean valueTaken(String objectApiName, int constraint, List<String> values, UUID except);

    /**
     * Switches the constraint on for up to {@code limit} live records of the object after the identifier, in
     * identifier order, that have a copy in each of its columns. The version and the time of last change of a record do
     * not change.
     *
     * @throws UniqueValueTakenException when two records hold the same value (nothing of the batch is written)
     */
    Filled maskUnique(String objectApiName, int constraint, UUID after, int limit);

    /** Whether a live record of the object has a copy in each column of the constraint and does not carry its bit. */
    boolean hasUnmasked(String objectApiName, int constraint);

    /** How many live records of the object share their copies with another live record in the constraint. */
    long collidingRecords(String objectApiName, int constraint);

    // ---- the recalculation of stored formula results (Sprint 16A, ADR-0113) ----

    /**
     * Rewrites stored formula results of a live record without a new version and without a new time of last change:
     * the recalculation is not a change a person made.
     *
     * @param expectedVersion the version the results were calculated from
     * @param set the new results
     * @param clear the formula fields that have no result any more
     * @return false when the record has another version now, is in the recycle bin or is gone (nothing is written;
     *         whoever changed it recalculated the formulas on the way, or the next pass will)
     */
    boolean updateCalculated(String objectApiName, UUID id, long expectedVersion, Map<String, Object> set,
            List<String> clear);
}
