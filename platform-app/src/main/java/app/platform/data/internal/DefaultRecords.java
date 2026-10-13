package app.platform.data.internal;

import app.platform.data.DataRecord;
import app.platform.data.QueryPlan;
import app.platform.data.QueryResult;
import app.platform.data.RecordQuery;
import app.platform.data.RecordSlice;
import app.platform.data.Records;
import app.platform.data.SlotCopy;
import app.platform.data.StorageStrategy;
import app.platform.data.UniqueValueTakenException;
import app.platform.security.ObjectAccess;
import app.platform.security.ObjectAction;
import app.platformapi.ApiException;
import app.platformapi.ErrorCode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The record store's rules (ADR-0084 to ADR-0096): the gate, the write pipeline, the storage, the audit record and the
 * event, in one transaction per call. The storage is {@link StorageStrategy}; nothing here names a table.
 *
 * <p>A write goes through {@link WritePipeline} between the gate and the storage. Whatever is handed back, to the
 * caller or to the endpoint, has the fields the member may not read left out ({@link FieldSecurity}): the stored
 * record is complete, what a member sees of it is not.
 */
@Service
@Transactional
class DefaultRecords implements Records {

    private static final String NO_RECORD = "This record does not exist.";

    private final RecordGate gate;
    private final WritePipeline pipeline;
    private final FieldSecurity security;
    private final StorageStrategy storage;
    private final RecordChanges changes;
    private final QueryService queries;
    private final RecordVisibility visibility;
    private final RecordLinks links;
    private final RecordRemoval removal;
    private final SearchIndexer searchIndex;
    private final SearchEngine search;

    DefaultRecords(RecordGate gate, WritePipeline pipeline, FieldSecurity security, StorageStrategy storage,
            RecordChanges changes, QueryService queries, RecordVisibility visibility,
            RecordLinks links, RecordRemoval removal, SearchIndexer searchIndex, SearchEngine search) {
        this.searchIndex = searchIndex;
        this.search = search;
        this.gate = gate;
        this.pipeline = pipeline;
        this.security = security;
        this.storage = storage;
        this.changes = changes;
        this.queries = queries;
        this.visibility = visibility;
        this.links = links;
        this.removal = removal;
    }

    @Override
    public DataRecord create(String objectApiName, String recordType, Map<String, Object> fields) {
        RecordGate.Passed passed = gate.pass(objectApiName, ObjectAction.CREATE);
        ObjectAccess access = access(passed);
        WritePipeline.Plan plan = pipeline.run(passed, access, null, recordType, fields, List.of());
        DataRecord created;
        try {
            created = storage.insert(passed.object().apiName(), plan.recordType(), passed.userId(),
                    passed.userId(), plan.set(), plan.slots());
        } catch (UniqueValueTakenException e) {
            throw taken(e, plan.slots(), access);
        }
        links.sync(passed.object(), created.id(), plan.set(), plan.clear(), passed.userId());
        searchIndex.index(passed.object(), created, null, passed.userId());
        changes.created(passed.userId(), created.objectApiName(), created.id(), created.version(), plan.names());
        return FieldSecurity.visible(created, access);
    }

    @Override
    @Transactional(readOnly = true)
    public DataRecord get(String objectApiName, UUID id) {
        RecordGate.Passed passed = gate.pass(objectApiName, ObjectAction.READ);
        ObjectAccess access = access(passed);
        // The same stage the list and the query ask what the member may see (ADR-0100, ADR-0115): a record the member
        // may not reach is answered exactly as a record that does not exist.
        Optional<QueryPlan.Node> reachable = visibility.constraintFor(passed, access);
        DataRecord found = reachable.isPresent()
                ? storage.find(passed.object().apiName(), id, reachable.get())
                : storage.find(passed.object().apiName(), id);
        if (found == null || found.deleted()) {
            throw ApiException.notFound(NO_RECORD);
        }
        return FieldSecurity.visible(found, access);
    }

    @Override
    public DataRecord update(String objectApiName, UUID id, long expectedVersion, String recordType,
            Map<String, Object> set, List<String> clear) {
        RecordGate.Passed passed = gate.pass(objectApiName, ObjectAction.UPDATE);
        DataRecord current = storage.find(passed.object().apiName(), id);
        if (current == null || current.deleted()) {
            throw whyNotChanged(current);
        }
        if (current.version() != expectedVersion) {
            throw new ApiException(ErrorCode.CONCURRENT_MODIFICATION);
        }
        ObjectAccess access = access(passed);
        WritePipeline.Plan plan = pipeline.run(passed, access, current, recordType, set, clear);
        DataRecord updated;
        try {
            updated = storage.update(passed.object().apiName(), id, expectedVersion, plan.recordType(),
                    plan.set(), plan.clear(), passed.userId(), plan.slots());
        } catch (UniqueValueTakenException e) {
            throw taken(e, plan.slots(), access);
        }
        if (updated == null) {
            throw whyNotChanged(storage.find(passed.object().apiName(), id));
        }
        links.sync(passed.object(), updated.id(), plan.set(), plan.clear(), passed.userId());
        searchIndex.index(passed.object(), updated, plan.names(), passed.userId());
        changes.updated(passed.userId(), updated.objectApiName(), updated.id(), updated.version(), plan.names());
        return FieldSecurity.visible(updated, access);
    }

    @Override
    public DataRecord delete(String objectApiName, UUID id) {
        RecordGate.Passed passed = gate.pass(objectApiName, ObjectAction.DELETE);
        DataRecord deleted = removal.delete(passed, id);
        if (deleted == null) {
            throw whyNotChanged(storage.find(passed.object().apiName(), id));
        }
        changes.deleted(passed.userId(), deleted.objectApiName(), deleted.id(), deleted.version());
        return FieldSecurity.visible(deleted, access(passed));
    }

    @Override
    public DataRecord restore(String objectApiName, UUID id) {
        RecordGate.Passed passed = gate.pass(objectApiName, ObjectAction.DELETE);
        DataRecord binned = storage.find(passed.object().apiName(), id);
        if (binned == null) {
            throw ApiException.notFound(NO_RECORD);
        }
        if (!binned.deleted()) {
            throw new ApiException(ErrorCode.CONFLICT, "This record is not in the recycle bin.");
        }
        DataRecord restored = removal.restore(passed, binned);
        if (restored == null) {
            throw new ApiException(ErrorCode.CONFLICT, "This record is not in the recycle bin.");
        }
        changes.restored(passed.userId(), restored.objectApiName(), restored.id(), restored.version());
        return FieldSecurity.visible(restored, access(passed));
    }

    @Override
    public void purge(String objectApiName, UUID id) {
        RecordGate.Passed passed = gate.pass(objectApiName, ObjectAction.DELETE);
        DataRecord found = storage.find(passed.object().apiName(), id);
        if (found == null) {
            throw ApiException.notFound(NO_RECORD);
        }
        if (!found.deleted()) {
            throw new ApiException(ErrorCode.CONFLICT,
                    "Only a record in the recycle bin can be removed for good. Delete it first.");
        }
        if (!storage.purge(passed.object().apiName(), id)) {
            throw ApiException.notFound(NO_RECORD);
        }
        changes.purged(passed.userId(), found.objectApiName(), found.id(), found.version());
    }

    @Override
    @Transactional(readOnly = true)
    public RecordSlice list(String objectApiName, UUID after, int limit) {
        RecordGate.Passed passed = gate.pass(objectApiName, ObjectAction.READ);
        return queries.list(passed, access(passed), after, limit);
    }

    @Override
    @Transactional(readOnly = true)
    public QueryResult query(String objectApiName, RecordQuery query) {
        RecordGate.Passed passed = gate.pass(objectApiName, ObjectAction.READ);
        return queries.run(passed, access(passed), query);
    }

    @Override
    @Transactional(readOnly = true)
    public QueryResult search(String objectApiName, String text, int limit, String cursor) {
        RecordGate.Passed passed = gate.pass(objectApiName, ObjectAction.READ);
        return search.search(passed, access(passed), text, limit, cursor);
    }

    @Override
    @Transactional(readOnly = true)
    public RecordSlice listDeleted(String objectApiName, UUID after, int limit) {
        RecordGate.Passed passed = gate.pass(objectApiName, ObjectAction.DELETE);
        return slice(storage.listDeleted(passed.object().apiName(), after, limit + 1), limit, access(passed));
    }

    /**
     * The answer to a duplicate the pipeline did not see (two writes at the same moment): the same words, at the same
     * place, as the pipeline's own answer. Nothing is stored; the transaction ends with the exception.
     */
    private static ApiException taken(UniqueValueTakenException refused, SlotCopy copies, ObjectAccess access) {
        String place = copies.uniquePlaces().get(refused.constraint());
        boolean pair = UniqueKey.ofBit(refused.constraint()).map(UniqueKey::isPair).orElse(false);
        return ApiException.validation(place == null ? RecordValues.PROBLEMS_KEY : FieldSecurity.place(access, place),
                pair ? WritePipeline.PAIR_TAKEN : WritePipeline.VALUE_TAKEN);
    }

    /** What the member may do with the object's fields: one question per request, however many records follow. */
    private ObjectAccess access(RecordGate.Passed passed) {
        return security.accessOf(passed.membershipId(), passed.object().apiName());
    }

    private static RecordSlice slice(List<DataRecord> fetched, int limit, ObjectAccess access) {
        boolean more = fetched.size() > limit;
        return new RecordSlice(FieldSecurity.visible(more ? fetched.subList(0, limit) : fetched, access), more);
    }

    /**
     * Why a change that matched no live record of the right version did not happen: there is no such record, it is in
     * the recycle bin, or somebody changed it first. None of the three says anything about values.
     */
    private static ApiException whyNotChanged(DataRecord found) {
        if (found == null) {
            return ApiException.notFound(NO_RECORD);
        }
        if (found.deleted()) {
            return new ApiException(ErrorCode.CONFLICT, "This record is in the recycle bin. Restore it first.");
        }
        return new ApiException(ErrorCode.CONCURRENT_MODIFICATION);
    }
}
