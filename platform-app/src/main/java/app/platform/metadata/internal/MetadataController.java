package app.platform.metadata.internal;

import app.platform.security.Ability;
import app.platformapi.ApiPaths;
import app.platformapi.ApiResponse;
import app.platformapi.CreateFieldRequest;
import app.platformapi.CreateObjectRequest;
import app.platformapi.FieldTypeView;
import app.platformapi.FieldView;
import app.platformapi.ObjectRelationshipsView;
import app.platformapi.ObjectSummaryView;
import app.platformapi.ObjectView;
import app.platformapi.UpdateFieldRequest;
import app.platformapi.UpdateObjectRequest;
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
 * The object manager's API (Sprint 10, ADR-0058 to ADR-0062). Thin: the rules are in {@link MetadataService}. Answered
 * only on an organization host; reading needs the ability {@code metadata.view}, changing needs
 * {@code metadata.manage};
 * the organization is the host's, never a parameter, and the database shows only its rows. Nothing here changes
 * what the platform defines: such a request is refused in words and recorded.
 */
@RestController
@Tag(name = "Metadata")
class MetadataController {

    /** A change made at once is a publication of one change, so it needs both abilities (ADR-0068). */
    private static final List<Ability> LIVE = List.of(Ability.METADATA_MANAGE, Ability.METADATA_PUBLISH);

    private final MetadataService service;
    private final MetadataAdministration administration;
    private final MetadataLifecycle lifecycle;
    private final ChangeCodec codec;
    private final Relationships relationships;

    MetadataController(MetadataService service, MetadataAdministration administration, MetadataLifecycle lifecycle,
            ChangeCodec codec, Relationships relationships) {
        this.service = service;
        this.administration = administration;
        this.lifecycle = lifecycle;
        this.codec = codec;
        this.relationships = relationships;
    }

    @GetMapping(ApiPaths.METADATA_FIELD_TYPES)
    @Operation(
            operationId = "listFieldTypes",
            summary = "The field types an organization can choose from",
            description = "With the settings each type takes and the constraints it allows. For members who may view "
                    + "objects and fields. NOT_FOUND on the platform host, FORBIDDEN without the ability.")
    ApiResponse<List<FieldTypeView>> fieldTypes() {
        return ApiResponse.of(administration.run("metadata.view", Ability.METADATA_VIEW,
                caller -> service.fieldTypes()));
    }

    @GetMapping(ApiPaths.METADATA_OBJECTS)
    @Operation(
            operationId = "listObjects",
            summary = "The objects of the organization",
            description = "The standard objects of the platform and the organization's own, with how many fields each "
                    + "has. For members who may view objects and fields. NOT_FOUND on the platform host, FORBIDDEN "
                    + "without the ability.")
    ApiResponse<List<ObjectSummaryView>> list() {
        return ApiResponse.of(administration.run("metadata.view", Ability.METADATA_VIEW, caller -> service.objects()));
    }

    @PostMapping(ApiPaths.METADATA_OBJECTS)
    @Operation(
            operationId = "createObject",
            summary = "Create a custom object",
            description = "The name is turned into the permanent API name with the ending __c. VALIDATION_ERROR for a "
                    + "name in use or not allowed, CONFLICT at the limit of objects. Audited.")
    ResponseEntity<ApiResponse<ObjectView>> create(@Valid @RequestBody CreateObjectRequest body) {
        ObjectView created = administration.runAll("metadata.object.create", LIVE, caller -> lifecycle.applyNow(
                caller, codec.now(Change.Kind.CREATE_OBJECT, body.name(), null, body), ObjectView.class));
        return ResponseEntity.status(HttpStatus.CREATED).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResponse.of(created));
    }

    @GetMapping(ApiPaths.METADATA_OBJECTS + "/{objectApiName}")
    @Operation(
            operationId = "getObject",
            summary = "One object with all its fields",
            description = "System fields first, then the standard ones, then the organization's own. NOT_FOUND for an "
                    + "object the organization does not have.")
    ApiResponse<ObjectView> get(@PathVariable String objectApiName) {
        return ApiResponse.of(administration.run("metadata.view", Ability.METADATA_VIEW,
                caller -> service.object(objectApiName)));
    }

    @PutMapping(ApiPaths.METADATA_OBJECTS + "/{objectApiName}")
    @Operation(
            operationId = "updateObject",
            summary = "Change the labels of a custom object",
            description = "The API name never changes. FORBIDDEN for an object the platform defines, "
                    + "CONCURRENT_MODIFICATION when someone changed it since it was read. Audited.")
    ApiResponse<ObjectView> update(@PathVariable String objectApiName, @Valid @RequestBody UpdateObjectRequest body) {
        return ApiResponse.of(administration.runAll("metadata.object.update", LIVE, caller -> lifecycle.applyNow(
                caller, codec.now(Change.Kind.UPDATE_OBJECT, objectApiName, null, body), ObjectView.class)));
    }

    @DeleteMapping(ApiPaths.METADATA_OBJECTS + "/{objectApiName}")
    @Operation(
            operationId = "deleteObject",
            summary = "Remove a custom object with its fields",
            description = "The permissions on the object and its fields end with it. CONFLICT while fields of other "
                    + "objects point to it or it has records. FORBIDDEN for an object the platform defines. Audited.")
    ResponseEntity<Void> delete(@PathVariable String objectApiName) {
        administration.runAll("metadata.object.delete", LIVE, caller -> lifecycle.applyNow(caller,
                codec.now(Change.Kind.DELETE_OBJECT, objectApiName, null, null), Boolean.class));
        return ResponseEntity.noContent().header(HttpHeaders.CACHE_CONTROL, "no-store").build();
    }

    @GetMapping(ApiPaths.METADATA_OBJECTS + "/{objectApiName}/relationships")
    @Operation(
            operationId = "getObjectRelationships",
            summary = "The relationships of an object, in both directions",
            description = "The objects this one points at (parents), the lists of other objects that point at it "
                    + "(children), and the objects related through a junction object (many-to-many). Read from the "
                    + "lookup and master-detail fields, with what happens to a child when its parent is removed. "
                    + "NOT_FOUND for an object the organization does not have.")
    ApiResponse<ObjectRelationshipsView> relationships(@PathVariable String objectApiName) {
        return ApiResponse.of(administration.run("metadata.view", Ability.METADATA_VIEW,
                caller -> relationships.of(objectApiName)));
    }

    @PostMapping(ApiPaths.METADATA_OBJECTS + "/{objectApiName}/fields")
    @Operation(
            operationId = "createField",
            summary = "Add a custom field to an object",
            description = "To a custom object, or to a standard object that allows it. The name is turned into the "
                    + "permanent API name with the ending __c. VALIDATION_ERROR lists every problem with the type, its "
                    + "settings and its constraints. Audited.")
    ResponseEntity<ApiResponse<FieldView>> createField(@PathVariable String objectApiName,
            @Valid @RequestBody CreateFieldRequest body) {
        FieldView created = administration.runAll("metadata.field.create", LIVE, caller -> lifecycle.applyNow(caller,
                codec.now(Change.Kind.CREATE_FIELD, objectApiName, null, body), FieldView.class));
        return ResponseEntity.status(HttpStatus.CREATED).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResponse.of(created));
    }

    @PutMapping(ApiPaths.METADATA_OBJECTS + "/{objectApiName}/fields/{fieldApiName}")
    @Operation(
            operationId = "updateField",
            summary = "Change a custom field",
            description = "The API name, the object and the type never change; a picklist keeps every value it had. "
                    + "FORBIDDEN for a field the platform defines, CONCURRENT_MODIFICATION when someone changed it "
                    + "since it was read. Audited.")
    ApiResponse<FieldView> updateField(@PathVariable String objectApiName, @PathVariable String fieldApiName,
            @Valid @RequestBody UpdateFieldRequest body) {
        return ApiResponse.of(administration.runAll("metadata.field.update", LIVE, caller -> lifecycle.applyNow(
                caller, codec.now(Change.Kind.UPDATE_FIELD, objectApiName, fieldApiName, body), FieldView.class)));
    }

    @DeleteMapping(ApiPaths.METADATA_OBJECTS + "/{objectApiName}/fields/{fieldApiName}")
    @Operation(
            operationId = "deleteField",
            summary = "Remove a custom field",
            description = "The permissions on the field end with it. FORBIDDEN for a field the platform defines. "
                    + "Audited.")
    ResponseEntity<Void> deleteField(@PathVariable String objectApiName, @PathVariable String fieldApiName) {
        administration.runAll("metadata.field.delete", LIVE, caller -> lifecycle.applyNow(caller,
                codec.now(Change.Kind.DELETE_FIELD, objectApiName, fieldApiName, null), Boolean.class));
        return ResponseEntity.noContent().header(HttpHeaders.CACHE_CONTROL, "no-store").build();
    }
}
