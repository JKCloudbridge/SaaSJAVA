package app.platform.platformadmin.internal;

import static app.platform.identity.PlatformRole.PLATFORM_ADMIN;

import app.platform.identity.PlatformPerson;
import app.platform.identity.PlatformRole;
import app.platform.identity.PlatformRoles;
import app.platformapi.ApiException;
import app.platformapi.ApiPaths;
import app.platformapi.ApiResponse;
import app.platformapi.GrantPlatformRoleRequest;
import app.platformapi.PlatformPersonView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The people part of the platform console (Sprint 6, ADR-0030): who holds a platform role, granting and revoking.
 * Platform administrators only, on the platform host only; every grant and revoke is audited, and the last platform
 * administrator cannot be removed (the database refuses it).
 */
@RestController
@Tag(name = "Platform people")
class PlatformPeopleController {

    private final PlatformCaller caller;
    private final PlatformRoles roles;

    PlatformPeopleController(PlatformCaller caller, PlatformRoles roles) {
        this.caller = caller;
        this.roles = roles;
    }

    @GetMapping(ApiPaths.PLATFORM_PEOPLE)
    @Operation(
            operationId = "listPlatformPeople",
            summary = "Who holds a platform role",
            description = "For platform administrators. Platform host only.")
    ApiResponse<List<PlatformPersonView>> list(Principal principal) {
        caller.require(principal, PLATFORM_ADMIN);
        return ApiResponse.of(roles.people().stream().map(PlatformPeopleController::view).toList());
    }

    @PostMapping(ApiPaths.PLATFORM_PEOPLE)
    @Operation(
            operationId = "grantPlatformRole",
            summary = "Give a platform role to a person",
            description = "The person must already have an active account. VALIDATION_ERROR when no active account has "
                    + "the address, CONFLICT when the person holds the role. Audited. For platform administrators.")
    ResponseEntity<ApiResponse<PlatformPersonView>> grant(@Valid @RequestBody GrantPlatformRoleRequest body,
            Principal principal) {
        UUID actor = caller.require(principal, PLATFORM_ADMIN);
        PlatformRole role;
        try {
            role = PlatformRole.valueOf(body.role());
        } catch (IllegalArgumentException e) {
            throw ApiException.validation("role", "Must be PLATFORM_ADMIN, PLATFORM_SUPPORT or PLATFORM_BILLING.");
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.of(view(roles.grant(actor, body.email(), role))));
    }

    @DeleteMapping(ApiPaths.PLATFORM_PEOPLE + "/{assignmentId}")
    @Operation(
            operationId = "revokePlatformRole",
            summary = "Take a platform role away",
            description = "CONFLICT for the last platform administrator. Audited. For platform administrators.")
    ResponseEntity<Void> revoke(@PathVariable UUID assignmentId, Principal principal) {
        roles.revoke(caller.require(principal, PLATFORM_ADMIN), assignmentId);
        return ResponseEntity.noContent().header(HttpHeaders.CACHE_CONTROL, "no-store").build();
    }

    private static PlatformPersonView view(PlatformPerson person) {
        return new PlatformPersonView(person.assignmentId(), person.email(), person.displayName(),
                person.role().name(), person.since());
    }
}
