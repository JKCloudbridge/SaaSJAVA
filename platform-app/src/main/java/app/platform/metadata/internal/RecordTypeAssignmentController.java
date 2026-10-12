package app.platform.metadata.internal;

import app.platform.metadata.RecordTypeAssignment;
import app.platform.security.Ability;
import app.platformapi.ApiPaths;
import app.platformapi.ApiResponse;
import app.platformapi.CreateRecordTypeAssignmentRequest;
import app.platformapi.RecordTypeAssignmentView;
import app.platformapi.UpdateRecordTypeAssignmentRequest;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * Which profiles may use a record type, and which type is the default of a profile (Sprint 15A, ADR-0098). Thin: the
 * rules are in {@link RecordTypeAssignmentService}. Answered only on an organization host; reading needs
 * {@code metadata.view}; a change is made live at once, which is a publication of one change, so it needs
 * {@code metadata.manage} and {@code metadata.publish} (ADR-0068). There is no screen for it before Sprint 19.
 */
@RestController
@Tag(name = "Metadata")
class RecordTypeAssignmentController {

    private static final List<Ability> LIVE = List.of(Ability.METADATA_MANAGE, Ability.METADATA_PUBLISH);
    private static final String OBJECT = ApiPaths.METADATA_OBJECTS + "/{objectApiName}";
    private static final String PATH = OBJECT + "/record-types/{recordType}/profiles";
    private static final String RULE = "A record type with no profile assigned is open to every profile; with at "
            + "least one it is open only to the profiles named. ";

    private final MetadataAdministration administration;
    private final RecordTypeAssignmentService service;
    private final MetadataLifecycle lifecycle;
    private final ChangeCodec codec;

    RecordTypeAssignmentController(MetadataAdministration administration, RecordTypeAssignmentService service,
            MetadataLifecycle lifecycle, ChangeCodec codec) {
        this.administration = administration;
        this.service = service;
        this.lifecycle = lifecycle;
        this.codec = codec;
    }

    @GetMapping(OBJECT + "/record-type-assignments")
    @Operation(
            operationId = "listRecordTypeAssignmentsOfObject",
            summary = "Every record type assignment of an object",
            description = RULE + "A profile that no longer exists has no name here; its assignment still restricts the "
                    + "record type until it is removed. NOT_FOUND for an object the organization does not have.")
    ApiResponse<List<RecordTypeAssignmentView>> listOfObject(@PathVariable String objectApiName) {
        return ApiResponse.of(administration.run("metadata.recordtypeassignment.list", Ability.METADATA_VIEW,
                caller -> service.list(objectApiName, null)));
    }

    @GetMapping(PATH)
    @Operation(
            operationId = "listRecordTypeAssignments",
            summary = "The profiles that may use a record type",
            description = RULE + "An empty list means the record type is open to every profile. NOT_FOUND for an "
                    + "object the organization does not have.")
    ApiResponse<List<RecordTypeAssignmentView>> list(@PathVariable String objectApiName,
            @PathVariable String recordType) {
        return ApiResponse.of(administration.run("metadata.recordtypeassignment.list", Ability.METADATA_VIEW,
                caller -> service.list(objectApiName, recordType)));
    }

    @GetMapping(PATH + "/{profileId}")
    @Operation(
            operationId = "getRecordTypeAssignment",
            summary = "One profile's use of a record type",
            description = "NOT_FOUND for an assignment the record type does not have.")
    ApiResponse<RecordTypeAssignmentView> get(@PathVariable String objectApiName, @PathVariable String recordType,
            @PathVariable String profileId) {
        return ApiResponse.of(administration.run("metadata.recordtypeassignment.get", Ability.METADATA_VIEW,
                caller -> service.get(objectApiName, recordType + "~" + profileId)));
    }

    @PostMapping(PATH)
    @Operation(
            operationId = "createRecordTypeAssignment",
            summary = "Let a profile use a record type",
            description = RULE + "Live at once, as a release of one change. The first assignment of a record type "
                    + "closes it to every other profile; the record type in the path is the one meant. With "
                    + "defaultForProfile the record type becomes the one a new record of the profile gets when it "
                    + "names none, and the profile's previous default of the object loses the mark. CONFLICT when the "
                    + "record type does not exist. VALIDATION_ERROR for a profile that does not exist or a default "
                    + "that is not in use. Audited.")
    ResponseEntity<ApiResponse<RecordTypeAssignmentView>> create(@PathVariable String objectApiName,
            @PathVariable String recordType, @Valid @RequestBody CreateRecordTypeAssignmentRequest body) {
        CreateRecordTypeAssignmentRequest request = new CreateRecordTypeAssignmentRequest(recordType,
                body.profileId(), body.defaultForProfile());
        String key = RecordTypeAssignment.keyOf(recordType, body.profileId());
        RecordTypeAssignmentView created = administration.runAll("metadata.recordtypeassignment.create", LIVE,
                caller -> lifecycle.applyNow(caller,
                        codec.now(Change.Kind.CREATE_RECORD_TYPE_ASSIGNMENT, objectApiName, key, request),
                        RecordTypeAssignmentView.class));
        return ResponseEntity.status(HttpStatus.CREATED).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResponse.of(created));
    }

    @PutMapping(PATH + "/{profileId}")
    @Operation(
            operationId = "updateRecordTypeAssignment",
            summary = "Make a record type the default of a profile, or take the mark off",
            description = "Live at once, as a release of one change. Making it the default takes the mark off the "
                    + "profile's previous default of the object. CONCURRENT_MODIFICATION when someone changed it "
                    + "since it was read. Audited.")
    ApiResponse<RecordTypeAssignmentView> update(@PathVariable String objectApiName, @PathVariable String recordType,
            @PathVariable UUID profileId, @Valid @RequestBody UpdateRecordTypeAssignmentRequest body) {
        String key = RecordTypeAssignment.keyOf(recordType, profileId);
        return ApiResponse.of(administration.runAll("metadata.recordtypeassignment.update", LIVE,
                caller -> lifecycle.applyNow(caller,
                        codec.now(Change.Kind.UPDATE_RECORD_TYPE_ASSIGNMENT, objectApiName, key, body),
                        RecordTypeAssignmentView.class)));
    }

    @DeleteMapping(PATH + "/{profileId}")
    @Operation(
            operationId = "deleteRecordTypeAssignment",
            summary = "Stop a profile from using a record type",
            description = RULE + "Removing the last assignment of a record type opens it to every profile again. "
                    + "Records that already have the type keep it. Live at once, as a release of one change. "
                    + "Audited.")
    ResponseEntity<Void> delete(@PathVariable String objectApiName, @PathVariable String recordType,
            @PathVariable UUID profileId) {
        String key = RecordTypeAssignment.keyOf(recordType, profileId);
        administration.runAll("metadata.recordtypeassignment.delete", LIVE, caller -> lifecycle.applyNow(caller,
                codec.now(Change.Kind.DELETE_RECORD_TYPE_ASSIGNMENT, objectApiName, key, null), Boolean.class));
        return ResponseEntity.noContent().header(HttpHeaders.CACHE_CONTROL, "no-store").build();
    }
}
