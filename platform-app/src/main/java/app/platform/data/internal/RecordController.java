package app.platform.data.internal;

import app.platform.data.DataRecord;
import app.platform.data.RecordSlice;
import app.platform.data.Records;
import app.platformapi.ApiException;
import app.platformapi.ApiPageResponse;
import app.platformapi.ApiPaths;
import app.platformapi.ApiResponse;
import app.platformapi.CreateRecordRequest;
import app.platformapi.Cursors;
import app.platformapi.PageRequest;
import app.platformapi.Pagination;
import app.platformapi.RecordView;
import app.platformapi.UpdateRecordRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
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
 * <p>The write endpoints are the <strong>first, narrow form</strong>: they check that the object is one the generic
 * store holds, that every key is a writable field of the object and that the values have a shape the store can keep.
 * They do not yet check a value against the type of its field, required fields, relationships, validation rules or
 * formulas, and there is no field-level or record-level security: those are Sprints 15 and 17.
 */
@RestController
@Tag(name = "Records")
class RecordController {

    private static final String RECORDS = ApiPaths.OBJECTS + "/{objectApiName}/records";
    private static final String BIN = ApiPaths.OBJECTS + "/{objectApiName}/recycle-bin";
    private static final String NARROW = "This is the first form of the endpoint: values are not yet checked against "
            + "their field's type, required fields, relationships, validation rules or formulas (Sprint 15), and "
            + "there is no record-level security (Sprint 17). ";
    private static final String UNKNOWN = "NOT_FOUND for an object that does not exist, that a platform module owns "
            + "(a user, a profile ...) or that the caller may not read: all three answer alike. ";

    private final Records records;

    RecordController(Records records) {
        this.records = records;
    }

    @PostMapping(RECORDS)
    @Operation(
            operationId = "createRecord",
            summary = "Create a record of an object",
            description = NARROW + UNKNOWN + "FORBIDDEN for a caller who may read the object but not create in it. "
                    + "VALIDATION_ERROR names a key that is not a writable field (a system, calculated or retired "
                    + "field cannot be written) and says why a value cannot be kept. The caller owns the record. "
                    + "Audited and announced by object, identifier, version and field names, never by values.")
    ResponseEntity<ApiResponse<RecordView>> create(@PathVariable String objectApiName,
            @Valid @RequestBody CreateRecordRequest body) {
        RecordView created = view(records.create(objectApiName, body.fields()));
        return ResponseEntity.status(HttpStatus.CREATED).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResponse.of(created));
    }

    @GetMapping(RECORDS)
    @Operation(
            operationId = "listRecords",
            summary = "The live records of an object, in identifier order",
            description = "One page at a time by the cursor of the previous page. No filter and no sorting: those are "
                    + "the query engine of Sprint 16. Until record-level security (Sprint 17) a member who may read "
                    + "the object reads every record of it in the organization. " + UNKNOWN)
    ApiPageResponse<RecordView> list(@PathVariable String objectApiName, @ParameterObject PageRequest page) {
        return page(records.list(objectApiName, after(page), page.limit()), page);
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
            description = NARROW + "Only the fields named change; a null value or a name in clear removes a value. "
                    + "The request carries the version that was read: CONCURRENT_MODIFICATION (409) when the record "
                    + "has another version now, and nothing is changed. CONFLICT (409) when the record is in the "
                    + "recycle bin. " + UNKNOWN + "FORBIDDEN without permission to update the object.")
    ApiResponse<RecordView> update(@PathVariable String objectApiName, @PathVariable UUID id,
            @Valid @RequestBody UpdateRecordRequest body) {
        return ApiResponse.of(view(records.update(objectApiName, id, body.version(), body.fields(), body.clear())));
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

    private static RecordView view(DataRecord record) {
        return new RecordView(record.id().toString(), record.objectApiName(), record.version(),
                record.ownerId().toString(), record.fields(), record.createdAt(), record.updatedAt(),
                record.deletedAt());
    }
}
