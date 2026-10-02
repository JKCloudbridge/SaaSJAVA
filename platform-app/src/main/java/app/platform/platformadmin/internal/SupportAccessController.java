package app.platform.platformadmin.internal;

import static app.platform.identity.PlatformRole.PLATFORM_ADMIN;
import static app.platform.identity.PlatformRole.PLATFORM_SUPPORT;

import app.platformapi.ApiPaths;
import app.platformapi.ApiResponse;
import app.platformapi.ApproveSupportAccessRequest;
import app.platformapi.SupportAccessRequestBody;
import app.platformapi.SupportAccessView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controlled support access (Sprint 6, ADR-0035), both sides. The platform side (platform host only; administrators and
 * support) asks, lists and withdraws a request. The organization side (an organization host only; the administrators of
 * that organization) lists, approves, denies and revokes: the organization is the host, never a name in the request.
 */
@RestController
@Tag(name = "Support access")
class SupportAccessController {

    private final PlatformCaller caller;
    private final SupportAccessService service;

    SupportAccessController(PlatformCaller caller, SupportAccessService service) {
        this.caller = caller;
        this.service = service;
    }

    // ---- the platform person ----

    @PostMapping(ApiPaths.PLATFORM_ORGANIZATIONS + "/{organizationId}/support-access")
    @Operation(
            operationId = "requestSupportAccess",
            summary = "Ask an organization for support access",
            description = "A reason and 15 to 240 minutes. Nothing is granted until an administrator of the "
                    + "organization approves it. One open request per person and organization (CONFLICT). For platform "
                    + "administrators and support.")
    ResponseEntity<Void> request(@PathVariable UUID organizationId,
            @Valid @RequestBody SupportAccessRequestBody body, Principal principal) {
        UUID actor = caller.require(principal, PLATFORM_ADMIN, PLATFORM_SUPPORT);
        service.request(actor, organizationId, body.reason(), body.minutes());
        return ResponseEntity.status(HttpStatus.CREATED).header(HttpHeaders.CACHE_CONTROL, "no-store").build();
    }

    @GetMapping(ApiPaths.PLATFORM_ORGANIZATIONS + "/{organizationId}/support-access")
    @Operation(
            operationId = "listOrganizationSupportAccess",
            summary = "The support-access requests of an organization",
            description = "For platform administrators and support.")
    ApiResponse<List<SupportAccessView>> listForOrganization(@PathVariable UUID organizationId, Principal principal) {
        caller.require(principal, PLATFORM_ADMIN, PLATFORM_SUPPORT);
        return ApiResponse.of(service.listFor(organizationId));
    }

    @PostMapping(ApiPaths.PLATFORM_ORGANIZATIONS + "/{organizationId}/support-access/{grantId}/cancel")
    @Operation(
            operationId = "cancelSupportAccessRequest",
            summary = "Withdraw your own open request",
            description = "Only the person who asked can. CONFLICT when it is no longer open.")
    ResponseEntity<Void> cancel(@PathVariable UUID organizationId, @PathVariable UUID grantId, Principal principal) {
        UUID actor = caller.require(principal, PLATFORM_ADMIN, PLATFORM_SUPPORT);
        service.cancel(actor, organizationId, grantId);
        return ResponseEntity.noContent().header(HttpHeaders.CACHE_CONTROL, "no-store").build();
    }

    // ---- the organization's administrators ----

    @GetMapping(ApiPaths.SUPPORT_ACCESS)
    @Operation(
            operationId = "listSupportAccess",
            summary = "Support-access requests of the organization",
            description = "Who asked, why, and what became of it. For the administrators of the organization of the "
                    + "host. NOT_FOUND on the platform host.")
    ApiResponse<List<SupportAccessView>> list() {
        return ApiResponse.of(service.list());
    }

    @PostMapping(ApiPaths.SUPPORT_ACCESS + "/{grantId}/approve")
    @Operation(
            operationId = "approveSupportAccess",
            summary = "Approve a support-access request",
            description = "Opens a window of at most the minutes asked for and four hours; it ends by itself. CONFLICT "
                    + "when the request is no longer open.")
    ResponseEntity<Void> approve(@PathVariable UUID grantId,
            @Valid @RequestBody(required = false) ApproveSupportAccessRequest body) {
        service.approve(grantId, body == null ? null : body.minutes());
        return noContent();
    }

    @PostMapping(ApiPaths.SUPPORT_ACCESS + "/{grantId}/deny")
    @Operation(
            operationId = "denySupportAccess",
            summary = "Deny a support-access request",
            description = "CONFLICT when the request is no longer open.")
    ResponseEntity<Void> deny(@PathVariable UUID grantId) {
        service.deny(grantId);
        return noContent();
    }

    @PostMapping(ApiPaths.SUPPORT_ACCESS + "/{grantId}/revoke")
    @Operation(
            operationId = "revokeSupportAccess",
            summary = "End an approved support access now",
            description = "CONFLICT when the access is not approved.")
    ResponseEntity<Void> revoke(@PathVariable UUID grantId) {
        service.revoke(grantId);
        return noContent();
    }

    private static ResponseEntity<Void> noContent() {
        return ResponseEntity.noContent().header(HttpHeaders.CACHE_CONTROL, "no-store").build();
    }
}
