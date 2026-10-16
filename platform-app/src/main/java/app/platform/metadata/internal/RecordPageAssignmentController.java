package app.platform.metadata.internal;

import app.platform.metadata.RecordPageAssignment;
import app.platform.security.Ability;
import app.platformapi.ApiPaths;
import app.platformapi.ApiResponse;
import app.platformapi.CreateRecordPageAssignmentRequest;
import app.platformapi.RecordPageAssignmentView;
import app.platformapi.RecordPageResolutionView;
import app.platformapi.UpdateRecordPageAssignmentRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Which record page an object uses for a profile and a record type (Sprint 20, ADR-0131). Thin: the rules are in
 * {@link RecordPageAssignmentService}. Answered only on an organization host; reading needs {@code metadata.view}; a
 * change is made live at once, which is a publication of one change, so it needs {@code metadata.manage} and
 * {@code metadata.publish} (ADR-0068). The member's own resolved record page, for reading and editing a record, is
 * {@code GET /objects/{object}/record-page} (module {@code data}), which needs no administrative ability.
 */
@RestController
@Tag(name = "Metadata")
class RecordPageAssignmentController {

    private static final List<Ability> LIVE = List.of(Ability.METADATA_MANAGE, Ability.METADATA_PUBLISH);
    private static final String PATH = ApiPaths.METADATA_OBJECTS + "/{objectApiName}/record-page-assignments";
    private static final String KEY_NOTE = "The key is default, type and a tilde and the API name of a record type "
            + "for the record-type-alone rule, the identifier of the profile, or the identifier of the profile, a "
            + "tilde and the API name of the record type.";

    private final MetadataAdministration administration;
    private final RecordPageAssignmentService service;
    private final MetadataLifecycle lifecycle;
    private final ChangeCodec codec;

    RecordPageAssignmentController(MetadataAdministration administration, RecordPageAssignmentService service,
            MetadataLifecycle lifecycle, ChangeCodec codec) {
        this.administration = administration;
        this.service = service;
        this.lifecycle = lifecycle;
        this.codec = codec;
    }

    @GetMapping(PATH)
    @Operation(
            operationId = "listRecordPageAssignments",
            summary = "The record page assignments of an object",
            description = "The default record page of the object and the record pages chosen for profiles and "
                    + "record types. A profile that no longer exists has no name here and is ignored when a record "
                    + "page is chosen. NOT_FOUND for an object the organization does not have.")
    ApiResponse<List<RecordPageAssignmentView>> list(@PathVariable String objectApiName) {
        return ApiResponse.of(administration.run("metadata.recordpageassignment.list", Ability.METADATA_VIEW,
                caller -> service.list(objectApiName)));
    }

    @GetMapping(PATH + "/{key}")
    @Operation(
            operationId = "getRecordPageAssignment",
            summary = "One record page assignment",
            description = KEY_NOTE + " NOT_FOUND for an assignment the object does not have.")
    ApiResponse<RecordPageAssignmentView> get(@PathVariable String objectApiName, @PathVariable String key) {
        return ApiResponse.of(administration.run("metadata.recordpageassignment.get", Ability.METADATA_VIEW,
                caller -> service.get(objectApiName, key)));
    }

    @GetMapping(ApiPaths.METADATA_OBJECTS + "/{objectApiName}/record-page-resolution")
    @Operation(
            operationId = "resolveRecordPage",
            summary = "Which record page an object uses for a profile and a record type",
            description = "The first rule of this order that applies: the profile together with the record type, "
                    + "the record type alone, the profile alone, the default record page of the object. The answer "
                    + "names the rule; NONE when the object has no record page.")
    ApiResponse<RecordPageResolutionView> resolve(@PathVariable String objectApiName,
            @RequestParam(required = false) UUID profileId, @RequestParam(required = false) String recordType) {
        return ApiResponse.of(administration.run("metadata.recordpageassignment.resolve", Ability.METADATA_VIEW,
                caller -> service.resolve(objectApiName, profileId, recordType)));
    }

    @PostMapping(PATH)
    @Operation(
            operationId = "createRecordPageAssignment",
            summary = "Choose a record page for a profile, a record type, or both, or the default of the object",
            description = "Live at once, as a release of one change. With no profile and no record type it is the "
                    + "default record page of the object. CONFLICT when the record page or record type does not "
                    + "exist. Audited.")
    ResponseEntity<ApiResponse<RecordPageAssignmentView>> create(@PathVariable String objectApiName,
            @Valid @RequestBody CreateRecordPageAssignmentRequest body) {
        String key = RecordPageAssignment.keyOf(body.profileId(),
                body.recordType() == null || body.recordType().isBlank() ? null : body.recordType().strip());
        RecordPageAssignmentView created = administration.runAll("metadata.recordpageassignment.create", LIVE,
                caller -> lifecycle.applyNow(caller,
                        codec.now(Change.Kind.CREATE_RECORD_PAGE_ASSIGNMENT, objectApiName, key, body),
                        RecordPageAssignmentView.class));
        return ResponseEntity.status(HttpStatus.CREATED).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResponse.of(created));
    }

    @PutMapping(PATH + "/{key}")
    @Operation(
            operationId = "updateRecordPageAssignment",
            summary = "Point a record page assignment at another record page",
            description = KEY_NOTE + " Live at once, as a release of one change. CONCURRENT_MODIFICATION when "
                    + "someone changed it since it was read. Audited.")
    ApiResponse<RecordPageAssignmentView> update(@PathVariable String objectApiName, @PathVariable String key,
            @Valid @RequestBody UpdateRecordPageAssignmentRequest body) {
        return ApiResponse.of(administration.runAll("metadata.recordpageassignment.update", LIVE,
                caller -> lifecycle.applyNow(caller,
                        codec.now(Change.Kind.UPDATE_RECORD_PAGE_ASSIGNMENT, objectApiName, key, body),
                        RecordPageAssignmentView.class)));
    }

    @DeleteMapping(PATH + "/{key}")
    @Operation(
            operationId = "deleteRecordPageAssignment",
            summary = "Remove a record page assignment",
            description = KEY_NOTE + " Live at once, as a release of one change. Audited.")
    ResponseEntity<Void> delete(@PathVariable String objectApiName, @PathVariable String key) {
        administration.runAll("metadata.recordpageassignment.delete", LIVE, caller -> lifecycle.applyNow(caller,
                codec.now(Change.Kind.DELETE_RECORD_PAGE_ASSIGNMENT, objectApiName, key, null), Boolean.class));
        return ResponseEntity.noContent().header(HttpHeaders.CACHE_CONTROL, "no-store").build();
    }
}
