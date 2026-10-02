package app.platform.identity.internal;

import app.platform.tenant.TenantContexts;
import app.platformapi.ApiException;
import app.platformapi.ApiPaths;
import app.platformapi.ApiResponse;
import app.platformapi.CreateOrganizationRequest;
import app.platformapi.ErrorCode;
import app.platformapi.OrganizationCreated;
import app.platformapi.OrganizationSummary;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * A signed-in person founds an organization (Sprint 4, ADR-0025). Answered only on the platform host: an organization
 * host already has a tenant, and a tenant is never chosen by the client.
 */
@RestController
@Tag(name = "Organizations")
class OrganizationController {

    private final OrganizationService organizations;
    private final SwitchService switching;
    private final TenantContexts contexts;
    private final boolean trustForwardedHost;

    OrganizationController(OrganizationService organizations, SwitchService switching, TenantContexts contexts,
            @Value("${platform.tenancy.trust-forwarded-host:false}") boolean trustForwardedHost) {
        this.organizations = organizations;
        this.switching = switching;
        this.contexts = contexts;
        this.trustForwardedHost = trustForwardedHost;
    }

    @PostMapping(ApiPaths.ORGANIZATIONS)
    @Operation(
            operationId = "createOrganization",
            summary = "Found an organization",
            description = "The signed-in person creates an organization and becomes its founding administrator. The "
                    + "short name (slug) becomes the first label of the organization's host name. Answers 201 with the "
                    + "host to sign in at. A name or slug that is not acceptable or not free is a VALIDATION_ERROR on "
                    + "its field; a person who already founded as many organizations as allowed is FORBIDDEN. Only on "
                    + "the platform host.")
    ResponseEntity<ApiResponse<OrganizationCreated>> create(@Valid @RequestBody CreateOrganizationRequest body,
            Principal principal, HttpServletRequest request) {
        if (contexts.current().isPresent()) {
            throw ApiException.notFound("This is not available at this address.");
        }
        OrganizationCreated created = organizations.found(userOf(principal), body.displayName(), body.slug(),
                RequestHost.authority(request, trustForwardedHost));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(created));
    }

    @GetMapping(ApiPaths.ORGANIZATIONS)
    @Operation(
            operationId = "listMyOrganizations",
            summary = "The organizations the signed-in person is an active member of",
            description = "For the organization switcher, on any host: the answer is the caller's own memberships, "
                    + "not the organization the host names. Each entry carries the host the server built for it.")
    ApiResponse<List<OrganizationSummary>> mine(Principal principal, HttpServletRequest request) {
        return ApiResponse.of(switching.mine(userOf(principal), RequestHost.authority(request, trustForwardedHost)));
    }

    private static UUID userOf(Principal principal) {
        if (principal == null) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED);
        }
        try {
            return UUID.fromString(principal.getName());
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED);
        }
    }
}
