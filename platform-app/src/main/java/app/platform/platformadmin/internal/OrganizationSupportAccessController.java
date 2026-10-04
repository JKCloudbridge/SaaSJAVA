package app.platform.platformadmin.internal;

import app.platformapi.ApiPaths;
import app.platformapi.ApiResponse;
import app.platformapi.ApproveSupportAccessRequest;
import app.platformapi.SupportAccessView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controlled support access (Sprint 6, ADR-0035), the organization's side: the administrators of the organization of
 * the
 * host list, approve, deny and revoke the requests of the platform's support people. Organization host only; the
 * organization is the host, never a name in the request, and who may decide is the organization's own ability
 * (support-access.manage), not a platform role: these are not platform functions (ADR-0052).
 */
@RestController
@Tag(name = "Support access")
class OrganizationSupportAccessController {

    private final SupportAccessService service;

    OrganizationSupportAccessController(SupportAccessService service) {
        this.service = service;
    }

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
