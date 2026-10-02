package app.platform.security.internal;

import app.platformapi.ApiPaths;
import app.platformapi.ApiResponse;
import app.platformapi.RoleView;
import app.platformapi.SaveRoleRequest;
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
 * The role hierarchy (Sprint 7, ADR-0042). Thin: the rules are in {@link RoleService}. Answered only on an organization
 * host; the organization is the host and an identifier of another organization is not found. A role decides which
 * records a member may see once records exist; it never gives an ability.
 */
@RestController
@Tag(name = "Access")
class RoleController {

    private final RoleService roles;

    RoleController(RoleService roles) {
        this.roles = roles;
    }

    @GetMapping(ApiPaths.ROLES)
    @Operation(
            operationId = "listRoles",
            summary = "List the roles of the organization",
            description = "The hierarchy as a flat list with each role's parent. For members who manage access, invite "
                    + "members or see members. NOT_FOUND on the platform host, FORBIDDEN without one of those "
                    + "abilities.")
    ApiResponse<List<RoleView>> list() {
        return ApiResponse.of(roles.list());
    }

    @PostMapping(ApiPaths.ROLES)
    @Operation(
            operationId = "createRole",
            summary = "Create a role",
            description = "VALIDATION_ERROR for a name in use or a parent of another organization. For members who "
                    + "manage access; audited.")
    ResponseEntity<ApiResponse<RoleView>> create(@Valid @RequestBody SaveRoleRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResponse.of(roles.create(body)));
    }

    @PutMapping(ApiPaths.ROLES + "/{roleId}")
    @Operation(
            operationId = "updateRole",
            summary = "Change or move a role",
            description = "CONFLICT when the move would put the role below itself or one of its sub-roles (a loop). "
                    + "NOT_FOUND for a role of another organization. Audited.")
    ApiResponse<RoleView> update(@PathVariable UUID roleId, @Valid @RequestBody SaveRoleRequest body) {
        return ApiResponse.of(roles.update(roleId, body));
    }

    @DeleteMapping(ApiPaths.ROLES + "/{roleId}")
    @Operation(
            operationId = "deleteRole",
            summary = "Remove a role",
            description = "CONFLICT while the role has sub-roles or members hold it. Audited.")
    ResponseEntity<Void> delete(@PathVariable UUID roleId) {
        roles.delete(roleId);
        return ResponseEntity.noContent().header(HttpHeaders.CACHE_CONTROL, "no-store").build();
    }
}
