package app.platform.data.internal;

import app.platformapi.ApiPaths;
import app.platformapi.ApiResponse;
import app.platformapi.BulkRecordsRequest;
import app.platformapi.BulkRecordsResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Bulk operations on the records of an object (Sprint 17, ADR-0122). Thin: the rules are in {@link BulkRecords}, which
 * calls the same code as the single endpoints once for each record.
 */
@RestController
@Tag(name = "Records")
class BulkController {

    private final BulkRecords bulk;

    BulkController(BulkRecords bulk) {
        this.bulk = bulk;
    }

    @PostMapping(ApiPaths.OBJECTS + "/{objectApiName}/records/bulk")
    @Operation(
            operationId = "bulkRecords",
            summary = "Create, change or delete many records of an object in one call",
            description = "Every record goes through exactly the steps the same single request goes through (the "
                    + "gate, field permissions, types, required fields, links, unique values, validation rules, "
                    + "formulas, auto-numbers) and is audited and announced like it, once for each record; there is "
                    + "no faster private path. The answer gives the outcome of each record at its position in the "
                    + "request: CREATED, UPDATED, DELETED, FAILED (with the code, message and problems a single "
                    + "request would have answered) or ROLLED_BACK. By default each record stands on its own: one "
                    + "that is refused is undone alone and the others are saved. With allOrNone one refusal undoes "
                    + "the whole call (committed is false, nothing is saved). A change carries the version that was "
                    + "read; a conflict is the outcome of that record. At most 200 records and 8 MiB per call "
                    + "(settings of the deployment): above that the call is refused before anything is read (400 "
                    + "VALIDATION_ERROR for the count, 413 PAYLOAD_TOO_LARGE for the size). One audit record per "
                    + "call counts what it did, never a value. NOT_FOUND for an object that does not exist, that a "
                    + "platform module owns or that the caller may not read; FORBIDDEN for a caller who may read "
                    + "the object but not do the operation in it (decided once for the whole call, by the same gate "
                    + "as a single request). Whether the caller may change or delete a particular record is judged "
                    + "for each record like a single request: its outcome is NOT_FOUND when the caller cannot read "
                    + "the record and FORBIDDEN when they can read it but may not do this to it.")
    ApiResponse<BulkRecordsResponse> bulk(@PathVariable String objectApiName,
            @Valid @RequestBody BulkRecordsRequest body) {
        return ApiResponse.of(bulk.run(objectApiName, body));
    }
}
