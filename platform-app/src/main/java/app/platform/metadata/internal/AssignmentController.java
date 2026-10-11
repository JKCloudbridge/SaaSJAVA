package app.platform.metadata.internal;

import app.platform.metadata.LayoutAssignment;
import app.platform.security.Ability;
import app.platformapi.ApiPaths;
import app.platformapi.ApiResponse;
import app.platformapi.CreateLayoutAssignmentRequest;
import app.platformapi.LayoutAssignmentView;
import app.platformapi.LayoutResolutionView;
import app.platformapi.UpdateLayoutAssignmentRequest;
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
 * Which page layout an object uses for a profile and a record type (Sprint 12, ADR-0071). Thin: the rules are in
 * {@link AssignmentService}. Answered only on an organization host; reading needs {@code metadata.view}; a change is
 * made live at once, which is a publication of one change, so it needs {@code metadata.manage} and
 * {@code metadata.publish} (ADR-0068).
 */
@RestController
@Tag(name = "Metadata")
class AssignmentController {

    private static final List<Ability> LIVE = List.of(Ability.METADATA_MANAGE, Ability.METADATA_PUBLISH);
    private static final String PATH = ApiPaths.METADATA_OBJECTS + "/{objectApiName}/layout-assignments";
    private static final String KEY_NOTE = "The key is default, the identifier of the profile, or the identifier of "
            + "the profile, a tilde and the API name of the record type.";

    private final MetadataAdministration administration;
    private final AssignmentService service;
    private final MetadataLifecycle lifecycle;
    private final ChangeCodec codec;

    AssignmentController(MetadataAdministration administration, AssignmentService service,
            MetadataLifecycle lifecycle, ChangeCodec codec) {
        this.administration = administration;
        this.service = service;
        this.lifecycle = lifecycle;
        this.codec = codec;
    }

    @GetMapping(PATH)
    @Operation(
            operationId = "listLayoutAssignments",
            summary = "The layout assignments of an object",
            description = "The default layout of the object and the layouts chosen for profiles, with or without a "
                    + "record type. A profile that no longer exists has no name here and is ignored when a layout is "
                    + "chosen. NOT_FOUND for an object the organization does not have.")
    ApiResponse<List<LayoutAssignmentView>> list(@PathVariable String objectApiName) {
        return ApiResponse.of(administration.run("metadata.layoutassignment.list", Ability.METADATA_VIEW,
                caller -> service.list(objectApiName)));
    }

    @GetMapping(PATH + "/{key}")
    @Operation(
            operationId = "getLayoutAssignment",
            summary = "One layout assignment",
            description = KEY_NOTE + " NOT_FOUND for an assignment the object does not have.")
    ApiResponse<LayoutAssignmentView> get(@PathVariable String objectApiName, @PathVariable String key) {
        return ApiResponse.of(administration.run("metadata.layoutassignment.get", Ability.METADATA_VIEW,
                caller -> service.get(objectApiName, key)));
    }

    @GetMapping(ApiPaths.METADATA_OBJECTS + "/{objectApiName}/layout-resolution")
    @Operation(
            operationId = "resolveLayout",
            summary = "Which layout an object uses for a profile and a record type",
            description = "The first rule of this order that applies: the profile together with the record type, "
                    + "the record type alone (the layout of the record type), the profile alone, the default layout "
                    + "of the object. The answer names the rule; NONE when the object has no layout.")
    ApiResponse<LayoutResolutionView> resolve(@PathVariable String objectApiName,
            @RequestParam(required = false) UUID profileId, @RequestParam(required = false) String recordType) {
        return ApiResponse.of(administration.run("metadata.layoutassignment.resolve", Ability.METADATA_VIEW,
                caller -> service.resolve(objectApiName, profileId, recordType)));
    }

    @PostMapping(PATH)
    @Operation(
            operationId = "createLayoutAssignment",
            summary = "Choose a layout for a profile (and a record type), or the default layout of the object",
            description = "Live at once, as a release of one change. With no profile it is the default layout of the "
                    + "object. A record type needs a profile: a layout for a record type alone is set on the record "
                    + "type. CONFLICT when the layout or record type does not exist. Audited.")
    ResponseEntity<ApiResponse<LayoutAssignmentView>> create(@PathVariable String objectApiName,
            @Valid @RequestBody CreateLayoutAssignmentRequest body) {
        String key = LayoutAssignment.keyOf(body.profileId(),
                body.recordType() == null || body.recordType().isBlank() ? null : body.recordType().strip());
        LayoutAssignmentView created = administration.runAll("metadata.layoutassignment.create", LIVE,
                caller -> lifecycle.applyNow(caller,
                        codec.now(Change.Kind.CREATE_LAYOUT_ASSIGNMENT, objectApiName, key, body),
                        LayoutAssignmentView.class));
        return ResponseEntity.status(HttpStatus.CREATED).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResponse.of(created));
    }

    @PutMapping(PATH + "/{key}")
    @Operation(
            operationId = "updateLayoutAssignment",
            summary = "Point a layout assignment at another layout",
            description = KEY_NOTE + " Live at once, as a release of one change. CONCURRENT_MODIFICATION when "
                    + "someone changed it since it was read. Audited.")
    ApiResponse<LayoutAssignmentView> update(@PathVariable String objectApiName, @PathVariable String key,
            @Valid @RequestBody UpdateLayoutAssignmentRequest body) {
        return ApiResponse.of(administration.runAll("metadata.layoutassignment.update", LIVE,
                caller -> lifecycle.applyNow(caller,
                        codec.now(Change.Kind.UPDATE_LAYOUT_ASSIGNMENT, objectApiName, key, body),
                        LayoutAssignmentView.class)));
    }

    @DeleteMapping(PATH + "/{key}")
    @Operation(
            operationId = "deleteLayoutAssignment",
            summary = "Remove a layout assignment",
            description = KEY_NOTE + " Live at once, as a release of one change. Audited.")
    ResponseEntity<Void> delete(@PathVariable String objectApiName, @PathVariable String key) {
        administration.runAll("metadata.layoutassignment.delete", LIVE, caller -> lifecycle.applyNow(caller,
                codec.now(Change.Kind.DELETE_LAYOUT_ASSIGNMENT, objectApiName, key, null), Boolean.class));
        return ResponseEntity.noContent().header(HttpHeaders.CACHE_CONTROL, "no-store").build();
    }
}
