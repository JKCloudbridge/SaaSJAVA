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
import app.platform.security.ShareLevel;
import app.platformapi.ApiException;
import app.platformapi.ApiPageResponse;
import app.platformapi.ApiPaths;
import app.platformapi.ApiResponse;
import app.platformapi.ChangeOwnerRequest;
import app.platformapi.CreateRecordRequest;
import app.platformapi.Cursors;
import app.platformapi.FormulaRecalculationView;
import app.platformapi.LinkedRecordView;
import app.platformapi.ObjectIndexStatusView;
import app.platformapi.QueriedRecordView;
import app.platformapi.QueryFilterNode;
import app.platformapi.QueryRequest;
import app.platformapi.PageRequest;
import app.platformapi.Pagination;
import app.platformapi.RecordShareView;
import app.platformapi.SearchRecordsRequest;
import app.platformapi.RecordView;
import app.platformapi.ShareRecordRequest;
import app.platformapi.UpdateRecordRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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
import org.springframework.web.bind.annotation.PutMapping;
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
 * caller may not read. Reads are limited to the records the caller may reach (Sprint 17, record-level security: the
 * owner, the default of the object, the role hierarchy, sharing rules, explicit shares, view-all and modify-all);
 * whether the caller may change a particular record is judged by the next sprint (17A).
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
            + "the record as it will be), the linked records, the unique fields (no other live record may hold the "
            + "same value; a record in the recycle bin does not count, and a text is compared exactly), the "
            + "validation rules, then the formulas, which are calculated and stored. Defaults and auto-numbers are "
            + "applied on create. Whether the caller may change this particular record is judged by the next sprint "
            + "(17A); reads "
            + "are already limited to the records the caller may reach. ";
    private static final String UNKNOWN = "NOT_FOUND for an object that does not exist, that a platform module owns "
            + "(a user, a profile ...) or that the caller may not read: all three answer alike. ";

    private final Records records;
    private final IndexStatusService indexes;
    private final FormulaRecalculationAdmin recalculation;
    private final RecordSharing sharing;

    RecordController(Records records, IndexStatusService indexes, FormulaRecalculationAdmin recalculation,
            RecordSharing sharing) {
        this.records = records;
        this.indexes = indexes;
        this.recalculation = recalculation;
        this.sharing = sharing;
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
                    + "the query endpoint. Only the records the caller may reach are listed: the ones they own, the "
                    + "ones below them in the role hierarchy, the ones a sharing rule or an explicit share gives them "
                    + "and, when the object is public, all of them. Fields the caller may not read are left out of "
                    + "every record. " + UNKNOWN)
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
                    + "condition to add), never a slow request. A formula field can be used in a filter or an order "
                    + "(it is read from the stored result, without an index, so the same rule applies), but only "
                    + "while the stored results are current for the object's formulas: after a formula is edited "
                    + "the answer is VALIDATION_ERROR saying the results are being brought up to date, until the "
                    + "recalculation has finished (see the formula-recalculation endpoint). Records without a value "
                    + "come last in both directions; ties are broken by the identifier. There is no total: the "
                    + "page says whether another follows and gives a cursor that is good only for the same query. "
                    + "Only the records the caller may reach are searched. " + UNKNOWN)
    ApiPageResponse<QueriedRecordView> query(@PathVariable String objectApiName,
            @Valid @RequestBody QueryRequest body) {
        QueryResult result = records.query(objectApiName, toQuery(body));
        List<QueriedRecordView> items = result.records().stream().map(RecordController::queried).toList();
        Pagination pagination = result.hasMore() ? Pagination.more(result.limit(), result.nextCursor())
                : Pagination.last(result.limit());
        return ApiPageResponse.of(items, pagination);
    }

    @PostMapping(RECORDS + "/search")
    @Operation(
            operationId = "searchRecords",
            summary = "Search the words of the searchable fields of an object",
            description = "A record must have every word of the text (accents and case do not matter); the last word "
                    + "counts as the beginning of a word, so a search can be run while a person types. The best "
                    + "matches come first (a match in the first searchable field counts most), then by identifier, "
                    + "one page at a time. Only the fields flagged as searchable that the caller may read are "
                    + "searched (a record never matches on a field the caller cannot see), and only the records "
                    + "the caller may reach are found; what comes back is shaped like the answer of a query and "
                    + "nothing is highlighted. A field is searched once its words are complete (the index status "
                    + "of the object says when). VALIDATION_ERROR for a text with no word, for an object with no "
                    + "searchable field the caller may read, and for a search whose every word is very common (type "
                    + "more). Search across objects is not offered. " + UNKNOWN)
    ApiPageResponse<QueriedRecordView> search(@PathVariable String objectApiName,
            @Valid @RequestBody SearchRecordsRequest body) {
        QueryResult result = records.search(objectApiName, body.text(), body.limit() == null ? 0 : body.limit(),
                body.cursor());
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
                    + "only when it is complete. Also, for each unique field and each junction: waiting for the copy "
                    + "of the field to be complete, enforcing (existing records are being checked in; new values "
                    + "are refused already), conflict (two existing records hold the same value: the count is "
                    + "given and the check resumes when the data is fixed) or enforced. Needs the ability to view "
                    + "metadata. Organization hosts only. " + UNKNOWN)
    ApiResponse<ObjectIndexStatusView> indexStatus(@PathVariable String objectApiName) {
        return ApiResponse.of(indexes.statusOf(objectApiName));
    }

    @GetMapping(ApiPaths.OBJECTS + "/{objectApiName}/formula-recalculation")
    @Operation(
            operationId = "getFormulaRecalculation",
            summary = "Whether the stored formula results of an object are current",
            description = "Editing a formula leaves the results stored in existing records as they were; a background "
                    + "pass rewrites the ones that differ, without a new version and without an audit record per "
                    + "record (one audit record per pass, with counts). The state says whether the results belong "
                    + "to the formulas as they are now (CURRENT) or not (NEEDED, PENDING, RUNNING, CLOSING), the "
                    + "progress of the pass in flight and the counts of the last one. While the results are not "
                    + "current a filter or an order on a formula field is refused. There is no screen for this "
                    + "yet. Needs the ability to view metadata. Organization hosts only. " + UNKNOWN)
    ApiResponse<FormulaRecalculationView> formulaRecalculation(@PathVariable String objectApiName) {
        return ApiResponse.of(recalculation.statusOf(objectApiName));
    }

    @PostMapping(ApiPaths.OBJECTS + "/{objectApiName}/formula-recalculation")
    @Operation(
            operationId = "startFormulaRecalculation",
            summary = "Start the recalculation of the stored formula results of an object",
            description = "Starts a pass over the live records of the object even when the results are believed to be "
                    + "current, for example after records were changed by a path that does not calculate formulas. A "
                    + "pass that is already waiting or running does the work: asking again changes nothing. The "
                    + "answer is the state (the pass runs in the background, in batches). CONFLICT for an object "
                    + "without formulas. There is no screen for this yet. Needs the ability to publish metadata. "
                    + "Organization hosts only. " + UNKNOWN)
    ResponseEntity<ApiResponse<FormulaRecalculationView>> startFormulaRecalculation(
            @PathVariable String objectApiName) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResponse.of(recalculation.start(objectApiName)));
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

    @GetMapping(RECORDS + "/{id}/shares")
    @Operation(
            operationId = "listRecordShares",
            summary = "Who a record is shared with",
            description = "For whoever may change the record. " + UNKNOWN + "NOT_FOUND also for a record the caller "
                    + "cannot reach; FORBIDDEN for a caller who can read the record but not change it.")
    ApiResponse<List<RecordShareView>> listShares(@PathVariable String objectApiName, @PathVariable UUID id) {
        return ApiResponse.of(sharing.list(objectApiName, id).stream().map(RecordController::share).toList());
    }

    @PutMapping(RECORDS + "/{id}/shares")
    @Operation(
            operationId = "shareRecord",
            summary = "Share a record with a user or a public group",
            description = "Sharing again with the same user or group changes the level. READ lets them read the "
                    + "record, READ_WRITE lets them read and change it; a share never gives the right to delete. "
                    + "Visible at the next question on every instance. Needs the right to change the record. "
                    + "VALIDATION_ERROR for the owner, for someone who is not a member, for a group that does not "
                    + "exist; CONFLICT at the most shares a record can have. Audited by identifiers, never by "
                    + "values. " + UNKNOWN)
    ApiResponse<RecordShareView> share(@PathVariable String objectApiName, @PathVariable UUID id,
            @Valid @RequestBody ShareRecordRequest body) {
        return ApiResponse.of(share(sharing.share(objectApiName, id, body.granteeType(), body.granteeId(),
                body.accessLevel())));
    }

    @DeleteMapping(RECORDS + "/{id}/shares/{granteeId}")
    @Operation(
            operationId = "unshareRecord",
            summary = "Take a share back",
            description = "NOT_FOUND when the record is not shared with that user or group. Needs the right to change "
                    + "the record. Audited. " + UNKNOWN)
    ResponseEntity<Void> unshare(@PathVariable String objectApiName, @PathVariable UUID id,
            @PathVariable UUID granteeId) {
        sharing.unshare(objectApiName, id, granteeId);
        return ResponseEntity.noContent().header(HttpHeaders.CACHE_CONTROL, "no-store").build();
    }

    @PostMapping(RECORDS + "/{id}/owner")
    @Operation(
            operationId = "changeRecordOwner",
            summary = "Give a record to another owner",
            description = "The new owner must be an active member. The request carries the version that was read: "
                    + "CONCURRENT_MODIFICATION (409) when the record has another version now. Needs the right to "
                    + "change the record. The record keeps its shares. Audited and announced like a change of "
                    + "the record. " + UNKNOWN)
    ApiResponse<RecordView> changeOwner(@PathVariable String objectApiName, @PathVariable UUID id,
            @Valid @RequestBody ChangeOwnerRequest body) {
        return ApiResponse.of(view(sharing.changeOwner(objectApiName, id, body.ownerId(), body.version())));
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
        return FilterNodes.toFilter(node, place);
    }

    private static QueriedRecordView queried(QueriedRecord queried) {
        Map<String, LinkedRecordView> linked = new LinkedHashMap<>();
        queried.linked().forEach((field, link) -> linked.put(field,
                new LinkedRecordView(link.id().toString(), link.fields())));
        return new QueriedRecordView(view(queried.record()), linked);
    }

    private static RecordShareView share(ShareStore.ShareRow row) {
        return new RecordShareView(row.id(), row.granteeType(), row.granteeId(),
                row.level() == ShareLevel.READ_WRITE.value() ? "READ_WRITE" : "READ", row.sharedAt(), row.sharedBy());
    }

    private static RecordView view(DataRecord record) {
        return new RecordView(record.id().toString(), record.objectApiName(), record.recordType(), record.version(),
                record.ownerId().toString(), record.fields(), record.createdAt(), record.updatedAt(),
                record.deletedAt());
    }
}
