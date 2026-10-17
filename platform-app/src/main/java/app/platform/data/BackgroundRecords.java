package app.platform.data;

import app.platformapi.QueryFilterNode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * What the platform's own background work may read of records (Sprint 22, ADR-0125, ADR-0137): the automation of
 * workflows judges and selects records <em>as the platform</em>, never as a member. It never changes a record, an
 * owner, a share or a version; changing goes through {@link Records}, which is where the rules of writing live.
 *
 * <p>There is no field security and no record-level security here, on purpose: a workflow is a definition an
 * administrator made, it runs without a person behind it, and it must see what it was defined to react to. What it may
 * <em>do</em> with what it sees is decided by its actions, which are a closed list; the actions that change records do
 * so only through {@link BackgroundWrites} (Sprint 23, ADR-0139), the one reviewed place where background work writes.
 * Never reachable from a request: callers are background jobs inside an organization's own tenant context.
 *
 * <p>Reads go through the query engine's own stages (the same statement as a member's query), so a field name or a
 * value is never spliced into SQL.
 */
public interface BackgroundRecords {

    /**
     * The record, live or in the recycle bin.
     *
     * @return the record, or empty when there is none (never made, or removed for good)
     */
    Optional<DataRecord> find(String objectApiName, UUID id);

    /**
     * The values of the record as the expression engine takes them, by field API name (a field with no value, a system
     * field and a value of the wrong kind are not in the map).
     *
     * @throws IllegalStateException when the organization has no such object
     */
    Map<String, Object> engineValues(String objectApiName, DataRecord record);

    /**
     * One page of the live records of the object that meet the selection, in identifier order. No refusal for a scan of
     * a large object: the work is bounded by its page, not by what a person's request may cost.
     *
     * @param selection a filter of the record query model, or null for every live record
     * @param afterId the identifier the page continues after, or null for the first page
     * @param limit the page size
     * @throws app.platformapi.ApiException {@code VALIDATION_ERROR} when the selection does not fit the query model
     */
    List<DataRecord> scan(String objectApiName, QueryFilterNode selection, UUID afterId, int limit);
}
