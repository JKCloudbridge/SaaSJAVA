package app.platform.data;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The records of the organization the thread's tenant context names, as the signed-in member may use them: the one
 * public contract of the record store (Sprint 14, ADR-0084). Other modules, and the endpoints of this one, read and
 * write records only through it, never through a table (ADR-0088).
 *
 * <p>Every call passes the same gate before anything is read or written ({@code RecordGate}): the member is the one the
 * host and the token name, the object exists in the organization's metadata and is one the generic store holds (a
 * record of an object that a platform module owns, such as a user or a profile, is changed only through that module),
 * and the member may use the object (the permission decision API of the security module). A member who may not see an
 * object and an object that does not exist get the same answer. Since Sprint 15 a write passes the write pipeline
 * after the gate (field permission, types, required fields, relationships, validation rules, formulas,
 * auto-numbers, record types: ADR-0091) and a read leaves out the fields the member may not read; Sprint 17 adds
 * record-level security to the gate.
 *
 * <p>Every call runs in one transaction that is opened after the tenant context. A change, its audit record and its
 * event commit together or not at all. Values never reach a log, an audit record, an event or an error message.
 *
 * @see StorageStrategy
 */
public interface Records {

    /**
     * Creates a record owned by the caller.
     *
     * @param objectApiName the object
     * @param recordType the API name of the record type, or null for the default type of the object (if it has one)
     * @param fields the values by field API name
     * @throws app.platformapi.ApiException {@code NOT_FOUND} for an object the caller may not use or that the store
     *         does not hold, {@code FORBIDDEN} for a caller who may read but not create, {@code VALIDATION_ERROR}
     *         listing every problem of the first stage of the write pipeline that found any, each with its place
     */
    DataRecord create(String objectApiName, String recordType, Map<String, Object> fields);

    /**
     * Reads a live record.
     *
     * @throws app.platformapi.ApiException {@code NOT_FOUND} when the object is not usable by the caller, or no live
     *         record of that object has the identifier in this organization (a record in the recycle bin is
     *         {@code NOT_FOUND} here too)
     */
    DataRecord get(String objectApiName, UUID id);

    /**
     * Changes the values of a live record. The fields named in {@code set} take the value, the fields in {@code clear}
     * lose theirs, the others keep theirs.
     *
     * @param expectedVersion the version the change is based on
     * @param recordType the record type to move the record to, or null to leave it as it is
     * @throws app.platformapi.ApiException {@code CONCURRENT_MODIFICATION} when the record has another version now
     *         (nothing is changed), {@code CONFLICT} when the record is in the recycle bin, {@code NOT_FOUND},
     *         {@code FORBIDDEN} and {@code VALIDATION_ERROR} as for {@link #create}
     */
    DataRecord update(String objectApiName, UUID id, long expectedVersion, String recordType,
            Map<String, Object> set, List<String> clear);

    /**
     * Moves a live record to the recycle bin. The second call finds it there and answers {@code CONFLICT}.
     *
     * @return the record as it is in the recycle bin
     */
    DataRecord delete(String objectApiName, UUID id);

    /**
     * Takes a record out of the recycle bin; needs the permission to delete the object. {@code CONFLICT} when the
     * record is not in the recycle bin.
     *
     * @return the live record
     */
    DataRecord restore(String objectApiName, UUID id);

    /**
     * Removes a record in the recycle bin for good; needs the permission to delete the object. {@code CONFLICT} when
     * the record is live (it must be deleted first).
     */
    void purge(String objectApiName, UUID id);

    /**
     * One page of the live records of the object in identifier order, read through the same stages as {@link #query}
     * (field permissions, record security, the plan) with no filter and no order.
     *
     * @param after the identifier of the last record of the previous page, or null for the first page
     * @param limit the page size, at least 1
     */
    RecordSlice list(String objectApiName, UUID after, int limit);

    /**
     * Answers a question put to the records of the object: a filter, an order, the fields to bring and the links to
     * follow, one page at a time (Sprint 16, ADR-0100). The query is a closed model, checked against the metadata and
     * against what the member may read (a field they may not read is a field that does not exist), planned, and turned
     * into a parameterized statement by the storage; nothing a person sends is ever run as code or spliced into a
     * statement. A question that would read every record of a large object is refused with the fields to flag as
     * indexed (ADR-0105). There is no total in the answer.
     *
     * @throws app.platformapi.ApiException {@code NOT_FOUND} for an object the caller may not use or that the store
     *         does not hold, {@code VALIDATION_ERROR} listing every problem of the first stage that found any, each
     *         with its place
     */
    QueryResult query(String objectApiName, RecordQuery query);

    /**
     * Searches the words of the object's searchable fields (Sprint 17, ADR-0124): a record must have every word of
     * the text, the last word counts as a beginning, the best matches come first, one page at a time. Only the
     * searchable fields the caller may read are searched, only the records the caller may reach are found, and the
     * answer is shaped like the answer of {@link #query}. A text whose rarest word is very common is refused with the
     * advice to type more.
     *
     * @param text what was typed
     * @param limit the page size, or 0 for the default
     * @param cursor the cursor of the previous page, or null
     * @throws app.platformapi.ApiException {@code NOT_FOUND} for an object the caller may not use or that the store
     *         does not hold, {@code VALIDATION_ERROR} for a text with no word, a text that is too broad, an object
     *         without a searchable field the caller may read, or a cursor that is not valid
     */
    QueryResult search(String objectApiName, String text, int limit, String cursor);

    /**
     * One page of the records in the recycle bin of the object, in identifier order; needs the permission to delete
     * the object.
     */
    RecordSlice listDeleted(String objectApiName, UUID after, int limit);
}
