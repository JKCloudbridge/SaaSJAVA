package app.platform.data.internal;

import app.platform.data.QueryPlan;
import app.platform.data.StorageStrategy;
import app.platform.metadata.Metadata;
import app.platform.metadata.ObjectDefinition;
import app.platform.security.Admission;
import app.platform.security.RecordMatcher;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Answers "is this one record among the records the admission describes?" with the statement a list runs (Sprint 17,
 * ADR-0115 point 2, ADR-0118): the admission becomes the same condition ({@link AdmissionPlans}) and the storage tests
 * it against the record. There is no second implementation of the rules, so the answer for one record and the answer
 * of a list are the same by construction. The security module reaches this class through {@link RecordMatcher}.
 */
@Component
class DataRecordMatcher implements RecordMatcher {

    private final Metadata metadata;
    private final AdmissionPlans plans;
    private final StorageStrategy storage;

    DataRecordMatcher(Metadata metadata, AdmissionPlans plans, StorageStrategy storage) {
        this.metadata = metadata;
        this.plans = plans;
        this.storage = storage;
    }

    @Override
    public boolean admits(String objectApiName, UUID recordId, Admission admission) {
        if (objectApiName == null || recordId == null || admission == null) {
            return false;
        }
        ObjectDefinition object = metadata.object(objectApiName).filter(found -> found.managedBy() == null)
                .orElse(null);
        if (object == null) {
            return false;
        }
        Optional<QueryPlan.Node> condition = plans.conditionOf(object, admission);
        return condition.isEmpty() ? !storage.liveIds(object.apiName(), List.of(recordId)).isEmpty()
                : storage.matches(object.apiName(), recordId, condition.get());
    }
}
