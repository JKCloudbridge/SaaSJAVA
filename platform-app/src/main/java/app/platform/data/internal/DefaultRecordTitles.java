package app.platform.data.internal;

import app.platform.data.DataRecord;
import app.platform.data.QueryPlan;
import app.platform.data.RecordTitles;
import app.platform.data.StorageStrategy;
import app.platform.metadata.FieldDefinition;
import app.platform.security.ObjectAccess;
import app.platform.security.ObjectAction;
import app.platformapi.ApiException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Names records for the person behind the request (ADR-0152), by the same stages a list passes: the gate, the object
 * and field permissions, and the statement of record-level security (ADR-0115). It is not transactional on purpose and
 * throws nothing for a record that cannot be seen, so using it never marks the caller's transaction rollback-only.
 */
@Component
class DefaultRecordTitles implements RecordTitles {

    private final RecordGate gate;
    private final FieldSecurity security;
    private final RecordVisibility visibility;
    private final StorageStrategy storage;

    DefaultRecordTitles(RecordGate gate, FieldSecurity security, RecordVisibility visibility,
            StorageStrategy storage) {
        this.gate = gate;
        this.security = security;
        this.visibility = visibility;
        this.storage = storage;
    }

    @Override
    public Map<UUID, String> titlesOf(String objectApiName, Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        RecordGate.Passed passed;
        try {
            passed = gate.pass(objectApiName, ObjectAction.READ);
        } catch (ApiException unreachable) {
            return Map.of();
        }
        ObjectAccess access = security.accessOf(passed.membershipId(), passed.object().apiName());
        Optional<QueryPlan.Node> reachable = visibility.constraintFor(passed, access);
        List<DataRecord> found = reachable.isPresent()
                ? storage.liveRecords(passed.object().apiName(), ids, reachable.get())
                : storage.liveRecords(passed.object().apiName(), ids);
        String title = TitleFields.of(passed.object(), access.readableFields()).map(FieldDefinition::apiName)
                .orElse(null);
        Map<UUID, String> titles = new LinkedHashMap<>();
        for (DataRecord record : found) {
            Object value = title == null ? null : record.fields().get(title);
            titles.put(record.id(), value == null ? "" : value.toString());
        }
        return titles;
    }
}
