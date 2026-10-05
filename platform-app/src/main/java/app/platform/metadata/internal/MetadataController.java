package app.platform.metadata.internal;

import app.platform.security.Ability;
import app.platformapi.ApiPaths;
import app.platformapi.ApiResponse;
import app.platformapi.CreateFieldRequest;
import app.platformapi.CreateObjectRequest;
import app.platformapi.FieldTypeView;
import app.platformapi.FieldView;
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

    private final MetadataService service;
    private final MetadataAdministration administration;

    MetadataController(MetadataService service, MetadataAdministration administration) {
        this.service = service;
        this.administration = administration;
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
        ObjectView created = administration.run("metadata.object.create", Ability.METADATA_MANAGE,
                caller -> service.createObject(caller, body));
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
        return ApiResponse.of(administration.run("metadata.object.update", Ability.METADATA_MANAGE,
                caller -> service.updateObject(caller, objectApiName, body)));
    }

    @DeleteMapping(ApiPaths.METADATA_OBJECTS + "/{objectApiName}")
    @Operation(
            operationId = "deleteObject",
            summary = "Remove a custom object with its fields",
            description = "The permissions on the object and its fields end with it. CONFLICT while fields of other "
                    + "objects point to it or it has records. FORBIDDEN for an object the platform defines. Audited.")
    ResponseEntity<Void> delete(@PathVariable String objectApiName) {
        administration.run("metadata.object.delete", Ability.METADATA_MANAGE, caller -> {
            service.deleteObject(caller, objectApiName);
            return Boolean.TRUE;
        });
        return ResponseEntity.noContent().header(HttpHeaders.CACHE_CONTROL, "no-store").build();
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
        FieldView created = administration.run("metadata.field.create", Ability.METADATA_MANAGE,
                caller -> service.createField(caller, objectApiName, body));
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
        return ApiResponse.of(administration.run("metadata.field.update", Ability.METADATA_MANAGE,
                caller -> service.updateField(caller, objectApiName, fieldApiName, body)));
    }

    @DeleteMapping(ApiPaths.METADATA_OBJECTS + "/{objectApiName}/fields/{fieldApiName}")
    @Operation(
            operationId = "deleteField",
            summary = "Remove a custom field",
            description = "The permissions on the field end with it. FORBIDDEN for a field the platform defines. "
                    + "Audited.")
    ResponseEntity<Void> deleteField(@PathVariable String objectApiName, @PathVariable String fieldApiName) {
        administration.run("metadata.field.delete", Ability.METADATA_MANAGE, caller -> {
            service.deleteField(caller, objectApiName, fieldApiName);
            return Boolean.TRUE;
        });
        return ResponseEntity.noContent().header(HttpHeaders.CACHE_CONTROL, "no-store").build();
    }
}
