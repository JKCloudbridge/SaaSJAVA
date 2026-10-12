package app.platform.data.internal;

import app.platform.data.DataRecord;
import app.platform.data.RecordSlice;
import app.platform.data.Records;
import app.platform.data.StorageStrategy;
import app.platform.security.ObjectAction;
import app.platformapi.ApiException;
import app.platformapi.ErrorCode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The record store's rules (ADR-0084 to ADR-0090): the gate, the checks of a write, the storage, the audit record and
 * the event, in one transaction per call. The storage is {@link StorageStrategy}; nothing here names a table.
 *
 * <p>Sprint 14 checks only what the prompt of the sprint lists: the object is held by the store, every key is a field
 * the caller may name, the values can be kept safely, and the caller may use the object. The write pipeline of Sprint
 * 15 (types, required fields, relationships, validation rules, formulas, field security) goes between the gate and the
 * storage, in the order of the delivery plan.
 */
@Service
@Transactional
class DefaultRecords implements Records {

    private static final String NO_RECORD = "This record does not exist.";

    private final RecordGate gate;
    private final RecordValues values;
    private final StorageStrategy storage;
    private final RecordChanges changes;

    DefaultRecords(RecordGate gate, RecordValues values, StorageStrategy storage, RecordChanges changes) {
        this.gate = gate;
        this.values = values;
        this.storage = storage;
        this.changes = changes;
    }

    @Override
    public DataRecord create(String objectApiName, Map<String, Object> fields) {
        RecordGate.Passed passed = gate.pass(objectApiName, ObjectAction.CREATE);
        RecordValues.Checked checked = values.check(passed.object(), fields, List.of(), false);
        DataRecord created = storage.insert(passed.object().apiName(), passed.userId(), passed.userId(),
                checked.set());
        changes.created(passed.userId(), created.objectApiName(), created.id(), created.version(), checked.names());
        return created;
    }

    @Override
    @Transactional(readOnly = true)
    public DataRecord get(String objectApiName, UUID id) {
        RecordGate.Passed passed = gate.pass(objectApiName, ObjectAction.READ);
        DataRecord found = storage.find(passed.object().apiName(), id);
        if (found == null || found.deleted()) {
            throw ApiException.notFound(NO_RECORD);
        }
        return found;
    }

    @Override
    public DataRecord update(String objectApiName, UUID id, long expectedVersion, Map<String, Object> set,
            List<String> clear) {
        RecordGate.Passed passed = gate.pass(objectApiName, ObjectAction.UPDATE);
        RecordValues.Checked checked = values.check(passed.object(), set, clear, true);
        DataRecord updated = storage.update(passed.object().apiName(), id, expectedVersion, checked.set(),
                checked.clear(), passed.userId());
        if (updated == null) {
            throw whyNotChanged(storage.find(passed.object().apiName(), id));
        }
        changes.updated(passed.userId(), updated.objectApiName(), updated.id(), updated.version(), checked.names());
        return updated;
    }

    @Override
    public DataRecord delete(String objectApiName, UUID id) {
        RecordGate.Passed passed = gate.pass(objectApiName, ObjectAction.DELETE);
        DataRecord deleted = storage.softDelete(passed.object().apiName(), id, passed.userId());
        if (deleted == null) {
            throw whyNotChanged(storage.find(passed.object().apiName(), id));
        }
        changes.deleted(passed.userId(), deleted.objectApiName(), deleted.id(), deleted.version());
        return deleted;
    }

    @Override
    public DataRecord restore(String objectApiName, UUID id) {
        RecordGate.Passed passed = gate.pass(objectApiName, ObjectAction.DELETE);
        DataRecord restored = storage.restore(passed.object().apiName(), id, passed.userId());
        if (restored == null) {
            DataRecord found = storage.find(passed.object().apiName(), id);
            if (found == null) {
                throw ApiException.notFound(NO_RECORD);
            }
            throw new ApiException(ErrorCode.CONFLICT, "This record is not in the recycle bin.");
        }
        changes.restored(passed.userId(), restored.objectApiName(), restored.id(), restored.version());
        return restored;
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
        return slice(storage.list(passed.object().apiName(), after, limit + 1), limit);
    }

    @Override
    @Transactional(readOnly = true)
    public RecordSlice listDeleted(String objectApiName, UUID after, int limit) {
        RecordGate.Passed passed = gate.pass(objectApiName, ObjectAction.DELETE);
        return slice(storage.listDeleted(passed.object().apiName(), after, limit + 1), limit);
    }

    private static RecordSlice slice(List<DataRecord> fetched, int limit) {
        boolean more = fetched.size() > limit;
        return new RecordSlice(more ? fetched.subList(0, limit) : fetched, more);
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
