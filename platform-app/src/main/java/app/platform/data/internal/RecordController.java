package app.platform.data.internal;

import app.platform.data.DataRecord;
import app.platform.data.QueriedRecord;
import app.platform.data.QueryFilter;
import app.platform.data.QueryResolve;
import app.platform.data.QueryResult;
import app.platform.data.QuerySort;
import app.platform.data.RecordQuery;
import app.platform.data.RecordSlice;
import app.platform.data.Records;
import app.platformapi.ApiException;
import app.platformapi.ApiPageResponse;
import app.platformapi.ApiPaths;
import app.platformapi.ApiResponse;
import app.platformapi.CreateRecordRequest;
import app.platformapi.Cursors;
import app.platformapi.LinkedRecordView;
import app.platformapi.ObjectIndexStatusView;
import app.platformapi.QueriedRecordView;
import app.platformapi.QueryFilterNode;
import app.platformapi.QueryRequest;
import app.platformapi.PageRequest;
import app.platformapi.Pagination;
import app.platformapi.RecordView;
import app.platformapi.UpdateRecordRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The records of an object and its recycle bin (Sprint 14, ADR-0084 to ADR-0089). Thin: the rules are in
 * {@link Records}. Answered only on an organization host, for a signed-in member; what a member may do is decided by
 * their permissions on the object (the permission decision API), never by a role of the platform. The organization is
 * the host's, never a parameter: a record identifier is not proof of anything.
 *
 * <p>A write goes through the pipeline of Sprint 15 (ADR-0091): record type, field permission, the type of each value,
 * required fields, linked records, validation rules and formulas, in that order, and a read leaves out the fields the
 * caller may not read. There is still no record-level security (Sprint 17): until then a member who may read an object
 * reads every record of it.
 */
@RestController
@Tag(name = "Records")
class RecordController {

    private static final String RECORDS = ApiPaths.OBJECTS + "/{objectApiName}/records";
    private static final String BIN = ApiPaths.OBJECTS + "/{objectApiName}/recycle-bin";
    private static final String PIPELINE = "The write goes through these stages in this order, and the first stage "
            + "that finds problems answers VALIDATION_ERROR with all of its problems, each with its place, and "
            + "saves nothing: the record type (it exists, is in use and offers the fields written), the field "
            + "permission (every field written must be one the caller may edit; a field the caller may not read is "
            + "answered like a field that does not exist), the type of each value, the required fields (judged on "
            + "the record as it will be), the linked records, the validation rules, then the formulas, which are "
            + "calculated and stored. Defaults and auto-numbers are applied on create. Unique fields are not yet "
            + "enforced (Sprint 16), and there is no record-level security (Sprint 17). ";
    private static final String UNKNOWN = "NOT_FOUND for an object that does not exist, that a platform module owns "
            + "(a user, a profile ...) or that the caller may not read: all three answer alike. ";

    private final Records records;
    private final IndexStatusService indexes;

    RecordController(Records records, IndexStatusService indexes) {
        this.records = records;
        this.indexes = indexes;
    }

    @PostMapping(RECORDS)
    @Operation(
            operationId = "createRecord",
            summary = "Create a record of an object",
            description = PIPELINE + UNKNOWN + "FORBIDDEN for a caller who may read the object but not create in "
                    + "it. The caller owns the record. The answer leaves out the fields the caller may not read. "
                    + "Audited and announced by object, identifier, version and field names, never by values.")
    ResponseEntity<ApiResponse<RecordView>> create(@PathVariable String objectApiName,
            @Valid @RequestBody CreateRecordRequest body) {
        RecordView created = view(records.create(objectApiName, body.recordType(), body.fields()));
        return ResponseEntity.status(HttpStatus.CREATED).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResponse.of(created));
    }

    @GetMapping(RECORDS)
    @Operation(
            operationId = "listRecords",
            summary = "The live records of an object, in identifier order",
            description = "One page at a time by the cursor of the previous page. For a filter, an order or links use "
                    + "the query endpoint. Until record-level security (Sprint 17) a member who may read "
                    + "the object reads every record of it in the organization. Fields the caller may not read are "
                    + "left out of every record. " + UNKNOWN)
    ApiPageResponse<RecordView> list(@PathVariable String objectApiName, @ParameterObject PageRequest page) {
        return page(records.list(objectApiName, after(page), page.limit()), page);
    }

    @PostMapping(RECORDS + "/query")
    @Operation(
            operationId = "queryRecords",
            summary = "Ask a question of the records of an object: filter, order, fields, links, one page at a time",
            description = "The body is a query model, never SQL: a tree of conditions (AND, OR, and comparisons by "
                    + "the kind of field), at most two sort keys, the fields to bring and the links to follow (one "
                    + "level). Names are looked up in the metadata and values are data. A field the caller may not "
                    + "read is answered like a field that does not exist, in a filter, an order, a field list or a "
                    + "link, so the answer never reveals what the caller may not see; records leave out such fields. "
                    + "The first stage that finds problems answers VALIDATION_ERROR with all its problems and their "
                    + "places, in the order: shape, fields, operators and values. A filter or an order on a field "
                    + "without a complete index is allowed only on an object with few records; on a larger one the "
                    + "answer is VALIDATION_ERROR at 'query' saying which field to flag as indexed (or which "
                    + "condition to add), never a slow request. A formula field cannot be used in a filter or an "
                    + "order yet. Records without a value come last in both directions; ties are broken by the "
                    + "identifier. There is no total: the page says whether another follows and gives a cursor that "
                    + "is good only for the same query. Not built yet: record-level security (Sprint 17), "
                    + "uniqueness (Sprint 16A). " + UNKNOWN)
    ApiPageResponse<QueriedRecordView> query(@PathVariable String objectApiName,
            @Valid @RequestBody QueryRequest body) {
        QueryResult result = records.query(objectApiName, toQuery(body));
        List<QueriedRecordView> items = result.records().stream().map(RecordController::queried).toList();
        Pagination pagination = result.hasMore() ? Pagination.more(result.limit(), result.nextCursor())
                : Pagination.last(result.limit());
        return ApiPageResponse.of(items, pagination);
    }

    @GetMapping(ApiPaths.OBJECTS + "/{objectApiName}/index-status")
    @Operation(
            operationId = "getObjectIndexStatus",
            summary = "Where the indexed fields of an object stand",
            description = "For each field flagged as indexed (and each field whose index is being removed): waiting, "
                    + "filling with the number of records done, complete, or clearing. Queries use a field's index "
                    + "only when it is complete. Needs the ability to view metadata. Organization hosts only. "
                    + UNKNOWN)
    ApiResponse<ObjectIndexStatusView> indexStatus(@PathVariable String objectApiName) {
        return ApiResponse.of(indexes.statusOf(objectApiName));
    }

    @GetMapping(RECORDS + "/{id}")
    @Operation(
            operationId = "getRecord",
            summary = "One record",
            description = UNKNOWN + "NOT_FOUND also for a record that does not exist in this organization, or that is "
                    + "in the recycle bin.")
    ApiResponse<RecordView> get(@PathVariable String objectApiName, @PathVariable UUID id) {
        return ApiResponse.of(view(records.get(objectApiName, id)));
    }

    @PatchMapping(RECORDS + "/{id}")
    @Operation(
            operationId = "updateRecord",
            summary = "Change some values of a record",
            description = PIPELINE + "Only the fields named change; a null value or a name in clear removes a value. "
                    + "The request carries the version that was read: CONCURRENT_MODIFICATION (409) when the record "
                    + "has another version now, and nothing is changed. CONFLICT (409) when the record is in the "
                    + "recycle bin. " + UNKNOWN + "FORBIDDEN without permission to update the object.")
    ApiResponse<RecordView> update(@PathVariable String objectApiName, @PathVariable UUID id,
            @Valid @RequestBody UpdateRecordRequest body) {
        return ApiResponse.of(view(records.update(objectApiName, id, body.version(), body.recordType(),
                body.fields(), body.clear())));
    }

    @DeleteMapping(RECORDS + "/{id}")
    @Operation(
            operationId = "deleteRecord",
            summary = "Move a record to the recycle bin",
            description = "The record can be restored for the retention period, then it is removed for good. A second "
                    + "delete of the same record is a CONFLICT (409): it is already in the recycle bin. " + UNKNOWN
                    + "FORBIDDEN without permission to delete in the object.")
    ResponseEntity<Void> delete(@PathVariable String objectApiName, @PathVariable UUID id) {
        records.delete(objectApiName, id);
        return ResponseEntity.noContent().header(HttpHeaders.CACHE_CONTROL, "no-store").build();
    }

    @GetMapping(BIN)
    @Operation(
            operationId = "listDeletedRecords",
            summary = "The records of an object that are in the recycle bin",
            description = "In identifier order, one page at a time. Needs permission to delete in the object. "
                    + UNKNOWN)
    ApiPageResponse<RecordView> listDeleted(@PathVariable String objectApiName, @ParameterObject PageRequest page) {
        return page(records.listDeleted(objectApiName, after(page), page.limit()), page);
    }

    @PostMapping(BIN + "/{id}/restore")
    @Operation(
            operationId = "restoreRecord",
            summary = "Take a record out of the recycle bin",
            description = "Needs permission to delete in the object. CONFLICT (409) for a record that is not in the "
                    + "recycle bin. " + UNKNOWN)
    ApiResponse<RecordView> restore(@PathVariable String objectApiName, @PathVariable UUID id) {
        return ApiResponse.of(view(records.restore(objectApiName, id)));
    }

    @DeleteMapping(BIN + "/{id}")
    @Operation(
            operationId = "purgeRecord",
            summary = "Remove a record in the recycle bin for good",
            description = "Needs permission to delete in the object. CONFLICT (409) for a live record: delete it "
                    + "first. This cannot be undone. " + UNKNOWN)
    ResponseEntity<Void> purge(@PathVariable String objectApiName, @PathVariable UUID id) {
        records.purge(objectApiName, id);
        return ResponseEntity.noContent().header(HttpHeaders.CACHE_CONTROL, "no-store").build();
    }

    /** The identifier a cursor stands for; a cursor this API did not make is the caller's mistake. */
    private static UUID after(PageRequest page) {
        if (page.cursor() == null) {
            return null;
        }
        try {
            return Cursors.decode(page.cursor()).map(UUID::fromString)
                    .orElseThrow(() -> new IllegalArgumentException("not a cursor"));
        } catch (IllegalArgumentException e) {
            throw ApiException.validation("cursor", "Is not a valid cursor");
        }
    }

    private static ApiPageResponse<RecordView> page(RecordSlice slice, PageRequest request) {
        List<RecordView> items = slice.records().stream().map(RecordController::view).toList();
        Pagination pagination = slice.hasMore()
                ? Pagination.more(request.limit(), Cursors.encode(items.get(items.size() - 1).id()))
                : Pagination.last(request.limit());
        return ApiPageResponse.of(items, pagination);
    }

    // ---- the request as a query model ----

    private static RecordQuery toQuery(QueryRequest body) {
        QueryFilter filter = body.filter() == null ? null : node(body.filter(), "filter");
        List<QuerySort> sort = body.sort() == null ? List.of() : body.sort().stream()
                .map(key -> new QuerySort(key.field(), Boolean.TRUE.equals(key.descending()))).toList();
        List<QueryResolve> resolve = body.resolve() == null ? List.of() : body.resolve().stream()
                .map(link -> new QueryResolve(link.field(), link.fields())).toList();
        return new RecordQuery(filter, sort, body.fields(), resolve, body.limit() == null ? 0 : body.limit(),
                body.cursor());
    }

    private static QueryFilter node(QueryFilterNode node, String place) {
        QueryFilter.Op op;
        try {
            op = QueryFilter.Op.valueOf(node.op());
        } catch (IllegalArgumentException e) {
            throw ApiException.validation(place + ".op", "Choose a comparison from the list.");
        }
        List<QueryFilter> items = new ArrayList<>();
        if (node.items() != null) {
            for (int index = 0; index < node.items().size(); index++) {
                items.add(node(node.items().get(index), place + ".items[" + index + "]"));
            }
        }
        List<Object> values = node.values() == null ? List.of() : node.values();
        if (values.contains(null)) {
            throw ApiException.validation(place + ".values", "A value is missing.");
        }
        return new QueryFilter(op, items, node.field(), values);
    }

    private static QueriedRecordView queried(QueriedRecord queried) {
        Map<String, LinkedRecordView> linked = new LinkedHashMap<>();
        queried.linked().forEach((field, link) -> linked.put(field,
                new LinkedRecordView(link.id().toString(), link.fields())));
        return new QueriedRecordView(view(queried.record()), linked);
    }

    private static RecordView view(DataRecord record) {
        return new RecordView(record.id().toString(), record.objectApiName(), record.recordType(), record.version(),
                record.ownerId().toString(), record.fields(), record.createdAt(), record.updatedAt(),
                record.deletedAt());
    }
}
