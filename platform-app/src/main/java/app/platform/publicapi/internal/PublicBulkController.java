package app.platform.publicapi.internal;

import app.platform.data.IdempotentWrites;
import app.platform.data.RecordBulk;
import app.platform.publicapi.PublicBulkRequest;
import app.platform.publicapi.PublicBulkResponse;
import app.platformapi.ApiHeaders;
import app.platformapi.ApiPaths;
import app.platformapi.ApiResponse;
import app.platformapi.BulkRecordsRequest;
import app.platformapi.BulkRecordsResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Creates, changes or deletes many records of an object in one call (Sprint 30, ADR-0245). Thin: the rules are in
 * {@link RecordBulk}, which calls the same code as the single operations once for each record, in a savepoint of one
 * transaction.
 */
@RestController
@Tag(name = "Records")
class PublicBulkController {

    private final RecordBulk bulk;
    private final IdempotentWrites writes;
    private final PublicCaller caller;
    private final PublicApiProperties properties;

    PublicBulkController(RecordBulk bulk, IdempotentWrites writes, PublicCaller caller,
            PublicApiProperties properties) {
        this.bulk = bulk;
        this.writes = writes;
        this.caller = caller;
        this.properties = properties;
    }

    @PostMapping(ApiPaths.PUBLIC_OBJECTS + "/{object}/records/bulk")
    @Operation(
            operationId = "bulkRecords",
            summary = "Create, change or delete many records of an object in one call",
            description = "Every record goes through exactly the steps the same single request goes through (the "
                    + "gate, field permissions, types, required fields, links, unique values, validation rules, "
                    + "formulas, auto-numbers) and is audited and announced like it, once for each record; there is "
                    + "no faster private path. The answer gives the outcome of each record at its position in the "
                    + "request: CREATED, UPDATED, DELETED, FAILED (with the error a single request would have "
                    + "answered) or ROLLED_BACK. By default each record stands on its own: one that is refused is "
                    + "undone alone and the others are saved. With `allOrNone` one refusal undoes the whole call "
                    + "(`committed` is false, nothing is saved). A change carries, in its item, the version that was "
                    + "read (there is no If-Match here); a conflict is the outcome of that record. At most 200 "
                    + "records and 8 MiB per call. Send an `Idempotency-Key` to make the call safe to repeat: the "
                    + "same key with the same request, from the same user, is answered with the outcome of the first "
                    + "time (`Idempotent-Replayed: true`; a refused record keeps its code, with fixed words) instead "
                    + "of being carried out again; an all-or-none call that was rolled back leaves nothing behind. "
                    + "NOT_FOUND for an object that does not exist, that the platform itself owns or that the caller "
                    + "may not read; FORBIDDEN for a caller who may read the object but not do the operation in it "
                    + "(decided once for the whole call). Whether the caller may change or delete a particular "
                    + "record is judged for each record like a single request.",
            responses = {
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                        description = "The outcome of every record. The call itself succeeded even when records in "
                                + "it were refused: read `results`."),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                        description = "VALIDATION_ERROR for more than 200 records or an unknown property, or "
                                + "MALFORMED_REQUEST."),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                        description = "FORBIDDEN: the caller may not do this operation in this object."),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                        description = "CONFLICT: the Idempotency-Key was used for another request."),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "413",
                        description = "PAYLOAD_TOO_LARGE: the body is over 8 MiB."),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "415",
                        description = "UNSUPPORTED_MEDIA_TYPE: send application/json.")})
    ResponseEntity<ApiResponse<PublicBulkResponse>> bulk(@PathVariable("object") String object,
            @Parameter(description = "Your own key for this call, 8 to 255 characters. See the description.")
            @RequestHeader(value = ApiHeaders.IDEMPOTENCY_KEY, required = false) String key,
            @Valid @RequestBody PublicBulkRequest body) {
        BulkRecordsRequest request = PublicRecordMapper.bulk(body);
        BulkRecordsResponse response;
        boolean replayed = false;
        if (key == null) {
            response = bulk.run(object, request);
        } else {
            IdempotencyKeys.require(key);
            IdempotentWrites.Claim claim = new IdempotentWrites.Claim(caller.membership(),
                    IdempotencyKeys.keyHash(key), IdempotencyKeys.requestHash("BULK_RECORDS", object, body),
                    properties.idempotency().retention());
            IdempotentWrites.BulkOutcome outcome = writes.bulk(claim, object, request);
            response = outcome.response();
            replayed = outcome.replayed();
        }
        ResponseEntity.BodyBuilder answer = ResponseEntity.ok().cacheControl(CacheControl.noStore());
        if (replayed) {
            answer.header(ApiHeaders.IDEMPOTENT_REPLAYED, "true");
        }
        return answer.body(ApiResponse.of(PublicRecordMapper.bulk(response)));
    }
}
