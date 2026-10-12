package app.platform.metadata.internal;

import app.platform.security.Ability;
import app.platformapi.ApiPaths;
import app.platformapi.ApiResponse;
import app.platformapi.CreateRecordTypeRequest;
import app.platformapi.RecordTypeView;
import app.platformapi.UpdateRecordTypeRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The record types of an object (Sprint 11, ADR-0064). Thin: the rules are in {@link RecordTypeService}. Answered only
 * on an organization host; reading needs {@code metadata.view}; a change is made live at once, which is a publication
 * of one change, so it needs {@code metadata.manage} and {@code metadata.publish} (ADR-0068). Several changes that
 * belong together go through a change set instead.
 */
@RestController
@Tag(name = "Metadata")
class RecordTypeController {

    private static final List<Ability> LIVE = List.of(Ability.METADATA_MANAGE, Ability.METADATA_PUBLISH);
    private static final String PATH = ApiPaths.METADATA_OBJECTS + "/{objectApiName}/record-types";

    private final MetadataAdministration administration;
    private final RecordTypeService service;
    private final MetadataLifecycle lifecycle;
    private final ChangeCodec codec;

    RecordTypeController(MetadataAdministration administration, RecordTypeService service,
            MetadataLifecycle lifecycle, ChangeCodec codec) {
        this.administration = administration;
        this.service = service;
        this.lifecycle = lifecycle;
        this.codec = codec;
    }

    @GetMapping(PATH)
    @Operation(
            operationId = "listRecordTypes",
            summary = "The record types of an object",
            description = "For members who may view objects and fields. NOT_FOUND for an object the organization "
                    + "does not have, on the platform host.")
    ApiResponse<List<RecordTypeView>> list(@PathVariable String objectApiName) {
        return ApiResponse.of(administration.run("metadata.view", Ability.METADATA_VIEW,
                caller -> service.list(objectApiName)));
    }

    @GetMapping(PATH + "/{recordTypeApiName}")
    @Operation(
            operationId = "getRecordType",
            summary = "One record type",
            description = "NOT_FOUND for a record type the object does not have.")
    ApiResponse<RecordTypeView> get(@PathVariable String objectApiName, @PathVariable String recordTypeApiName) {
        return ApiResponse.of(administration.run("metadata.view", Ability.METADATA_VIEW,
                caller -> service.get(objectApiName, recordTypeApiName)));
    }

    @PostMapping(PATH)
    @Operation(
            operationId = "createRecordType",
            summary = "Add a record type to an object",
            description = "Live at once, as a release of one change. The name is turned into the permanent API name "
                    + "with the ending __c. VALIDATION_ERROR lists every problem with the fields and picklist values "
                    + "named; CONFLICT for an object whose records belong to the platform, at the limit, or when the "
                    + "result would break a dependency. Audited.")
    ResponseEntity<ApiResponse<RecordTypeView>> create(@PathVariable String objectApiName,
            @Valid @RequestBody CreateRecordTypeRequest body) {
        RecordTypeView created = administration.runAll("metadata.recordtype.create", LIVE,
                caller -> lifecycle.applyNow(caller,
                        codec.now(Change.Kind.CREATE_RECORD_TYPE, objectApiName, null, body), RecordTypeView.class));
        return ResponseEntity.status(HttpStatus.CREATED).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResponse.of(created));
    }

    @PutMapping(PATH + "/{recordTypeApiName}")
    @Operation(
            operationId = "updateRecordType",
            summary = "Change a record type",
            description = "Live at once, as a release of one change. The API name and the object never change. "
                    + "CONCURRENT_MODIFICATION when someone changed it since it was read. Audited.")
    ApiResponse<RecordTypeView> update(@PathVariable String objectApiName, @PathVariable String recordTypeApiName,
            @Valid @RequestBody UpdateRecordTypeRequest body) {
        return ApiResponse.of(administration.runAll("metadata.recordtype.update", LIVE,
                caller -> lifecycle.applyNow(caller,
                        codec.now(Change.Kind.UPDATE_RECORD_TYPE, objectApiName, recordTypeApiName, body),
                        RecordTypeView.class)));
    }

    @DeleteMapping(PATH + "/{recordTypeApiName}")
    @Operation(
            operationId = "deleteRecordType",
            summary = "Remove a record type",
            description = "Live at once, as a release of one change. NOT_FOUND for a record type the object does not "
                    + "have. Audited.")
    ResponseEntity<Void> delete(@PathVariable String objectApiName, @PathVariable String recordTypeApiName) {
        administration.runAll("metadata.recordtype.delete", LIVE, caller -> lifecycle.applyNow(caller,
                codec.now(Change.Kind.DELETE_RECORD_TYPE, objectApiName, recordTypeApiName, null), Boolean.class));
        return ResponseEntity.noContent().header(HttpHeaders.CACHE_CONTROL, "no-store").build();
    }
}
