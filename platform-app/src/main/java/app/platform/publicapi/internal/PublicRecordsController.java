package app.platform.publicapi.internal;

import app.platform.data.DataRecord;
import app.platform.data.IdempotentWrites;
import app.platform.data.Records;
import app.platform.publicapi.PublicCreateRequest;
import app.platform.publicapi.PublicRecord;
import app.platform.publicapi.PublicUpdateRequest;
import app.platformapi.ApiException;
import app.platformapi.ApiHeaders;
import app.platformapi.ApiPaths;
import app.platformapi.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Create, read, change, delete and restore one record (Sprint 30, ADR-0245). Thin: the rules are in {@link Records},
 * the same code the internal interface calls, so the gate, field security, record security, record locks, the write
 * pipeline, the audit trail and the events apply unchanged and nothing here decides who may see or change a record.
 * The caller is the member the host and the token name; the organization is the host's, never a parameter.
 */
@RestController
@Tag(name = "Records")
class PublicRecordsController {

    private static final String RECORDS = ApiPaths.PUBLIC_OBJECTS + "/{object}/records";
    private static final Pattern ETAG = Pattern.compile("^\"[0-9]{1,18}\"$");
    private static final String UNKNOWN = "NOT_FOUND for an object that does not exist, that the platform itself "
            + "owns, that belongs to another organization or that the caller may not read: all alike. ";
    private static final String PIPELINE = "The write goes through these stages in this order, and the first stage "
            + "that finds problems answers VALIDATION_ERROR with all of its problems, each with its place, and saves "
            + "nothing: the shape (every key must be a field of the object that the caller may write; a field the "
            + "caller may not read is answered like a field that does not exist), the record type, the field "
            + "permission, the type of each value (a number must be a JSON number), the required fields, the linked "
            + "records (only to a record the caller can read), the unique fields (the answer says the value is in use "
            + "and never whose record holds it), the validation rules, then the formulas, which are calculated and "
            + "stored. Defaults and auto-numbers are applied on create. ";

    private final Records records;
    private final IdempotentWrites writes;
    private final PublicCaller caller;
    private final PublicApiProperties properties;

    PublicRecordsController(Records records, IdempotentWrites writes, PublicCaller caller,
            PublicApiProperties properties) {
        this.records = records;
        this.writes = writes;
        this.caller = caller;
        this.properties = properties;
    }

    @PostMapping(RECORDS)
    @Operation(
            operationId = "createRecord",
            summary = "Create a record",
            description = PIPELINE + "The caller owns the record. The answer is the record as the caller may read it, "
                    + "with `Location` and the `ETag` (the version). Send an `Idempotency-Key` to make the call safe "
                    + "to repeat: the same key with the same request, from the same user, makes one record whatever "
                    + "the number of attempts, and the repeat is answered with `Idempotent-Replayed: true` and the "
                    + "record as it is now; the same key with another request is CONFLICT. A key is good for 24 "
                    + "hours. A refused request leaves nothing behind, so it can be sent again. " + UNKNOWN
                    + "FORBIDDEN for a caller who may read the object but not create in it.",
            responses = {
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201",
                        description = "The record was created (or, with `Idempotent-Replayed: true`, was created by "
                                + "an earlier request with the same key)."),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                        description = "VALIDATION_ERROR with the problems and their places; MALFORMED_REQUEST for a "
                                + "body that is not JSON. A property the contract does not have is refused with its "
                                + "place."),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                        description = "FORBIDDEN: the caller may read the object but not create in it."),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                        description = "CONFLICT: the Idempotency-Key was used for another request, or the record the "
                                + "first request made cannot be read any more."),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "413",
                        description = "PAYLOAD_TOO_LARGE: the body is over 1 MiB."),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "415",
                        description = "UNSUPPORTED_MEDIA_TYPE: send application/json.")})
    ResponseEntity<ApiResponse<PublicRecord>> create(@PathVariable("object") String object,
            @Parameter(description = "Your own key for this create, 8 to 255 characters. See the description.")
            @RequestHeader(value = ApiHeaders.IDEMPOTENCY_KEY, required = false) String key,
            @Valid @RequestBody PublicCreateRequest body) {
        DataRecord created;
        boolean replayed = false;
        if (key == null) {
            created = records.create(object, body.recordType(), body.fields());
        } else {
            IdempotentWrites.Claim claim = claim(IdempotencyKeys.require(key), "CREATE_RECORD", object, body);
            IdempotentWrites.Created outcome = writes.create(claim, object, body.recordType(), body.fields());
            created = outcome.record();
            replayed = outcome.replayed();
        }
        ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .location(URI.create(ApiPaths.PUBLIC_OBJECTS + "/" + object + "/records/" + created.id()))
                .eTag(etag(created.version()));
        if (replayed) {
            response.header(ApiHeaders.IDEMPOTENT_REPLAYED, "true");
        }
        return response.body(ApiResponse.of(PublicRecordMapper.record(created)));
    }

    @GetMapping(RECORDS + "/{id}")
    @Operation(
            operationId = "getRecord",
            summary = "Read one record",
            description = "The record as the caller may read it: a field the caller may not read is absent. The `ETag` "
                    + "is the version of the record. " + UNKNOWN + "NOT_FOUND also for a record that does not exist "
                    + "in this organization, that is in the recycle bin, or that the caller may not read (the "
                    + "default of the object, the role hierarchy, sharing rules and shares decide): all alike. An "
                    + "identifier that is not an identifier is VALIDATION_ERROR.",
            responses = @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "The record."))
    ResponseEntity<ApiResponse<PublicRecord>> get(@PathVariable("object") String object, @PathVariable("id") UUID id) {
        DataRecord found = records.get(object, id);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).eTag(etag(found.version()))
                .body(ApiResponse.of(PublicRecordMapper.record(found)));
    }

    @PatchMapping(RECORDS + "/{id}")
    @Operation(
            operationId = "updateRecord",
            summary = "Change some values of a record",
            description = PIPELINE + "A merge: only the fields named change; a null value or a name in `clear` "
                    + "removes a value. The version the change is based on is sent in the `If-Match` header, as the "
                    + "`ETag` of the read (`\"3\"`); it is required. CONCURRENT_MODIFICATION (409) when the record has "
                    + "another version now, and nothing is changed: read it again and decide. CONFLICT (409) when the "
                    + "record is in the recycle bin. RECORD_LOCKED (409) while an approval has the record locked. "
                    + "Before any value is judged, a change is judged like a read of the record: NOT_FOUND when the "
                    + "caller cannot read it, FORBIDDEN when they can read it but may not change it. " + UNKNOWN,
            responses = {
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                        description = "The record after the change, with its new `ETag`."),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                        description = "VALIDATION_ERROR (including a missing or malformed If-Match), or "
                                + "MALFORMED_REQUEST."),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                        description = "FORBIDDEN: the caller may not change this record or this object."),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                        description = "CONCURRENT_MODIFICATION, CONFLICT or RECORD_LOCKED."),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "413",
                        description = "PAYLOAD_TOO_LARGE: the body is over 1 MiB."),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "415",
                        description = "UNSUPPORTED_MEDIA_TYPE: send application/json.")})
    ResponseEntity<ApiResponse<PublicRecord>> update(@PathVariable("object") String object,
            @PathVariable("id") UUID id,
            @Parameter(description = "The ETag of the version you read, for example \"3\".", required = true,
                    example = "\"3\"")
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody PublicUpdateRequest body) {
        long version = versionOf(ifMatch);
        DataRecord changed = records.update(object, id, version, body.recordType(), body.fields(), body.clear());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).eTag(etag(changed.version()))
                .body(ApiResponse.of(PublicRecordMapper.record(changed)));
    }

    @DeleteMapping(RECORDS + "/{id}")
    @Operation(
            operationId = "deleteRecord",
            summary = "Move a record to the recycle bin",
            description = "The record can be restored for the retention period (30 days unless the deployment says "
                    + "otherwise), then it is removed for good. A second delete of the same record is CONFLICT: it is "
                    + "already in the recycle bin. A master takes its details with it only when the caller may delete "
                    + "them too. The records of some objects are kept: deleting one is CONFLICT. " + UNKNOWN
                    + "FORBIDDEN without permission to delete in the object, or for a record the caller can read but "
                    + "may not delete; NOT_FOUND for a record the caller cannot read. RECORD_LOCKED (409) while an "
                    + "approval has the record locked.",
            responses = {
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204",
                        description = "The record is in the recycle bin."),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                        description = "FORBIDDEN: the caller may not delete this record or in this object."),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                        description = "CONFLICT (already in the recycle bin, or a record that is kept) or "
                                + "RECORD_LOCKED.")})
    ResponseEntity<Void> delete(@PathVariable("object") String object, @PathVariable("id") UUID id) {
        records.delete(object, id);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @PostMapping(RECORDS + "/{id}/restore")
    @Operation(
            operationId = "restoreRecord",
            summary = "Take a record out of the recycle bin",
            description = "Needs permission to delete in the object and the right to delete the record: NOT_FOUND for "
                    + "a record the caller cannot read, FORBIDDEN for one they can read but may not delete. CONFLICT "
                    + "(409) for a record that is not in the recycle bin, or whose value in a unique field is in use "
                    + "now. The answer is the live record with its `ETag`. " + UNKNOWN,
            responses = {
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                        description = "The live record."),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                        description = "FORBIDDEN: the caller may not delete this record or in this object."),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                        description = "CONFLICT: not in the recycle bin, or a unique value is in use now.")})
    ResponseEntity<ApiResponse<PublicRecord>> restore(@PathVariable("object") String object,
            @PathVariable("id") UUID id) {
        DataRecord restored = records.restore(object, id);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).eTag(etag(restored.version()))
                .body(ApiResponse.of(PublicRecordMapper.record(restored)));
    }

    private IdempotentWrites.Claim claim(String key, String operation, String object, Object body) {
        return new IdempotentWrites.Claim(caller.membership(), IdempotencyKeys.keyHash(key),
                IdempotencyKeys.requestHash(operation, object, body), properties.idempotency().retention());
    }

    /** The ETag of a version: a quoted number. */
    static String etag(long version) {
        return "\"" + version + "\"";
    }

    /**
     * The version an If-Match header names.
     *
     * @throws ApiException {@code VALIDATION_ERROR} at {@code If-Match} when it is missing or is not the ETag of a
     *         record; the words never repeat what was sent
     */
    static long versionOf(String ifMatch) {
        if (ifMatch == null || !ETAG.matcher(ifMatch.trim()).matches()) {
            throw ApiException.validation(HttpHeaders.IF_MATCH,
                    "Is required. Send the ETag of the version you read, for example \"3\".");
        }
        String trimmed = ifMatch.trim();
        return Long.parseLong(trimmed.substring(1, trimmed.length() - 1));
    }
}
