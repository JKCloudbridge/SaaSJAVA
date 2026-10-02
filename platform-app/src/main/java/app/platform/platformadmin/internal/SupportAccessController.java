package app.platform.platformadmin.internal;

import static app.platform.identity.PlatformRole.PLATFORM_ADMIN;
import static app.platform.identity.PlatformRole.PLATFORM_SUPPORT;

import app.platformapi.ApiPaths;
import app.platformapi.ApiResponse;
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
 * Controlled support access (Sprint 6, ADR-0035), the platform side (platform host only; administrators and support):
 * asks, lists and withdraws a request. The organization's side is {@link OrganizationSupportAccessController}.
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

    @PlatformFunction({PLATFORM_ADMIN, PLATFORM_SUPPORT})
    @PostMapping(ApiPaths.PLATFORM_ORGANIZATIONS + "/{organizationId}/support-access")
    @Operation(
            operationId = "requestSupportAccess",
            summary = "Ask an organization for support access",
            description = "A reason and 15 to 240 minutes. Nothing is granted until an administrator of the "
                    + "organization approves it. One open request per person and organization (CONFLICT). For platform "
                    + "administrators and support.")
    ResponseEntity<Void> request(@PathVariable UUID organizationId,
            @Valid @RequestBody SupportAccessRequestBody body, Principal principal) {
        UUID actor = caller.person(principal);
        service.request(actor, organizationId, body.reason(), body.minutes());
        return ResponseEntity.status(HttpStatus.CREATED).header(HttpHeaders.CACHE_CONTROL, "no-store").build();
    }

    @PlatformFunction({PLATFORM_ADMIN, PLATFORM_SUPPORT})
    @GetMapping(ApiPaths.PLATFORM_ORGANIZATIONS + "/{organizationId}/support-access")
    @Operation(
            operationId = "listOrganizationSupportAccess",
            summary = "The support-access requests of an organization",
            description = "For platform administrators and support.")
    ApiResponse<List<SupportAccessView>> listForOrganization(@PathVariable UUID organizationId, Principal principal) {
        return ApiResponse.of(service.listFor(organizationId));
    }

    @PlatformFunction({PLATFORM_ADMIN, PLATFORM_SUPPORT})
    @PostMapping(ApiPaths.PLATFORM_ORGANIZATIONS + "/{organizationId}/support-access/{grantId}/cancel")
    @Operation(
            operationId = "cancelSupportAccessRequest",
            summary = "Withdraw your own open request",
            description = "Only the person who asked can. CONFLICT when it is no longer open.")
    ResponseEntity<Void> cancel(@PathVariable UUID organizationId, @PathVariable UUID grantId, Principal principal) {
        UUID actor = caller.person(principal);
        service.cancel(actor, organizationId, grantId);
        return ResponseEntity.noContent().header(HttpHeaders.CACHE_CONTROL, "no-store").build();
    }
}
