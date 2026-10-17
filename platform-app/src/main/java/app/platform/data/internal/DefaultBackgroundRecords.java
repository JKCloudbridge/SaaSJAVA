package app.platform.data.internal;

import app.platform.data.BackgroundRecords;
import app.platform.data.DataRecord;
import app.platform.data.QueryFilter;
import app.platform.data.StorageStrategy;
import app.platform.metadata.Metadata;
import app.platform.metadata.ObjectDefinition;
import app.platformapi.ApiException;
import app.platformapi.QueryFilterNode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The reads of background work (Sprint 22, ADR-0137): the storage for a single record and the query engine's own
 * {@code scan} for a page, so there is no second path to the records.
 */
@Component
class DefaultBackgroundRecords implements BackgroundRecords {

    private final StorageStrategy storage;
    private final QueryService queries;
    private final Metadata metadata;

    DefaultBackgroundRecords(StorageStrategy storage, QueryService queries, Metadata metadata) {
        this.storage = storage;
        this.queries = queries;
        this.metadata = metadata;
    }

    @Override
    public Optional<DataRecord> find(String objectApiName, UUID id) {
        return Optional.ofNullable(storage.find(objectApiName, id));
    }

    @Override
    public Map<String, Object> engineValues(String objectApiName, DataRecord record) {
        ObjectDefinition object = metadata.object(objectApiName).orElseThrow(
                () -> new IllegalStateException("The organization has no such object"));
        return Calculations.engineValues(object, record.fields());
    }

    @Override
    public List<DataRecord> scan(String objectApiName, QueryFilterNode selection, UUID afterId, int limit) {
        ObjectDefinition object = metadata.object(objectApiName).filter(found -> found.managedBy() == null)
                .orElseThrow(() -> ApiException.validation("object", "The records of this object are not kept here."));
        QueryFilter filter = selection == null ? null : FilterNodes.toFilter(selection, "selection");
        return queries.scan(object, filter, afterId, limit);
    }
}
