package app.platform.publicapi.internal;

import app.platform.data.DataRecord;
import app.platform.data.ObjectDescriptions;
import app.platform.data.QueriedRecord;
import app.platform.data.QueryFilter;
import app.platform.data.QueryResolve;
import app.platform.data.QuerySort;
import app.platform.data.RecordQuery;
import app.platform.publicapi.PublicBulkError;
import app.platform.publicapi.PublicBulkItem;
import app.platform.publicapi.PublicBulkOperation;
import app.platform.publicapi.PublicBulkRequest;
import app.platform.publicapi.PublicBulkResponse;
import app.platform.publicapi.PublicBulkResult;
import app.platform.publicapi.PublicBulkStatus;
import app.platform.publicapi.PublicField;
import app.platform.publicapi.PublicFilter;
import app.platform.publicapi.PublicLinkedRecord;
import app.platform.publicapi.PublicObjectDescription;
import app.platform.publicapi.PublicObjectSummary;
import app.platform.publicapi.PublicPicklistValue;
import app.platform.publicapi.PublicQueryRequest;
import app.platform.publicapi.PublicRecord;
import app.platform.publicapi.PublicRecordType;
import app.platformapi.ApiException;
import app.platformapi.BulkRecordItem;
import app.platformapi.BulkRecordResult;
import app.platformapi.BulkRecordsRequest;
import app.platformapi.BulkRecordsResponse;
import app.platformapi.FormFieldView;
import app.platformapi.ObjectFormView;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The only place the shape of the public API is known (Sprint 30, ADR-0245): it turns what the record store gives into
 * the public shape and a public body into what the record store takes. Pure: no database, no framework, no rule about
 * who may see or change anything (that is decided by the record store, once, for both doors).
 *
 * <p>Values pass through untouched. A number keeps the digits it was given (the request is read with decimals as
 * decimals and a record keeps what it was given), a field the caller may not read was already left out by the record
 * store and so is absent here, never null.
 */
final class PublicRecordMapper {

    /** A filter bigger than this is refused before the query engine counts its own, lower, limit. */
    static final int MAX_FILTER_NODES = 200;

    /** A filter deeper than this is refused so that reading it cannot exhaust the stack. */
    static final int MAX_FILTER_DEPTH = 16;

    private PublicRecordMapper() {
    }

    // ---- records out ----

    static PublicRecord record(DataRecord record) {
        return new PublicRecord(record.id().toString(), record.objectApiName(), record.recordType(),
                record.version(), record.ownerId().toString(), record.createdAt(), record.updatedAt(),
                record.deletedAt(), record.fields(), null);
    }

    static PublicRecord record(QueriedRecord queried) {
        Map<String, PublicLinkedRecord> linked = new LinkedHashMap<>();
        queried.linked().forEach((field, link) -> linked.put(field,
                new PublicLinkedRecord(link.id().toString(), link.fields())));
        DataRecord record = queried.record();
        return new PublicRecord(record.id().toString(), record.objectApiName(), record.recordType(),
                record.version(), record.ownerId().toString(), record.createdAt(), record.updatedAt(),
                record.deletedAt(), record.fields(), linked);
    }

    // ---- a query in ----

    static RecordQuery query(PublicQueryRequest body) {
        QueryFilter filter = body.filter() == null ? null : filter(body.filter(), "filter", new int[] {0}, 1);
        List<QuerySort> sort = body.sort() == null ? List.of() : body.sort().stream()
                .map(key -> new QuerySort(key.field(), Boolean.TRUE.equals(key.descending()))).toList();
        List<QueryResolve> resolve = body.resolve() == null ? List.of() : body.resolve().stream()
                .map(link -> new QueryResolve(link.field(), link.fields())).toList();
        return new RecordQuery(filter, sort, body.fields(), resolve, body.limit() == null ? 0 : body.limit(),
                body.cursor());
    }

    private static QueryFilter filter(PublicFilter node, String place, int[] counted, int depth) {
        counted[0]++;
        if (counted[0] > MAX_FILTER_NODES || depth > MAX_FILTER_DEPTH) {
            throw ApiException.validation("filter", "The filter is too large or too deeply nested.");
        }
        QueryFilter.Op op = QueryFilter.Op.valueOf(node.op().name());
        List<QueryFilter> items = new ArrayList<>();
        if (node.items() != null) {
            for (int index = 0; index < node.items().size(); index++) {
                items.add(filter(node.items().get(index), place + ".items[" + index + "]", counted, depth + 1));
            }
        }
        List<Object> values = node.values() == null ? List.of() : node.values();
        if (values.stream().anyMatch(value -> value == null)) {
            throw ApiException.validation(place + ".values", "A value is missing.");
        }
        return new QueryFilter(op, items, node.field(), values);
    }

    // ---- a bulk call in and out ----

    static BulkRecordsRequest bulk(PublicBulkRequest body) {
        List<BulkRecordItem> items = new ArrayList<>();
        for (PublicBulkItem item : body.records()) {
            items.add(new BulkRecordItem(item.id(), item.version(), item.recordType(), item.fields(),
                    item.clear()));
        }
        return new BulkRecordsRequest(body.operation().name(), body.allOrNone(), items);
    }

    static PublicBulkResponse bulk(BulkRecordsResponse response) {
        List<PublicBulkResult> results = new ArrayList<>();
        for (BulkRecordResult result : response.results()) {
            PublicBulkError error = result.code() == null ? null
                    : new PublicBulkError(result.code(), result.message(), result.fields());
            results.add(new PublicBulkResult(result.position(), PublicBulkStatus.valueOf(result.status()),
                    result.id() == null ? null : result.id().toString(), result.version(), error));
        }
        return new PublicBulkResponse(PublicBulkOperation.valueOf(response.operation()), response.allOrNone(),
                response.committed(), response.succeeded(), response.failed(), results);
    }

    // ---- the description of objects ----

    static PublicObjectSummary summary(ObjectDescriptions.Described object) {
        return new PublicObjectSummary(object.apiName(), object.label(), object.pluralLabel(), object.kind(),
                object.canCreate(), object.canUpdate(), object.canDelete());
    }

    static PublicObjectDescription description(ObjectFormView view) {
        List<PublicRecordType> types = view.recordTypes().stream()
                .filter(type -> Boolean.TRUE.equals(type.selectable()))
                .map(type -> new PublicRecordType(type.apiName(), type.label(), type.defaultType())).toList();
        return new PublicObjectDescription(view.objectApiName(), view.label(), view.pluralLabel(),
                view.actions().create(), view.actions().update(), view.actions().delete(), types,
                view.fields().stream().map(PublicRecordMapper::field).toList());
    }

    private static PublicField field(FormFieldView field) {
        List<PublicPicklistValue> values = field.values() == null ? null : field.values().stream()
                .map(value -> new PublicPicklistValue(value.value(), value.label(), value.active())).toList();
        return new PublicField(field.apiName(), field.label(), field.description(), field.kind(), field.type(),
                field.required(), field.unique(), field.editable(), field.calculated(), field.maxLength(),
                field.digits(), field.precision(), field.scale(), values, field.targetObject(), field.resultType(),
                field.searchable(), field.externalId(), field.sortable(), field.filterOperators());
    }
}
