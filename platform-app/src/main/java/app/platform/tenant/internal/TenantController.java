package app.platform.tenant.internal;

import app.platform.tenant.Tenant;
import app.platform.tenant.TenantContexts;
import app.platform.tenant.Tenants;
import app.platformapi.ApiException;
import app.platformapi.ApiPaths;
import app.platformapi.ApiResponse;
import app.platformapi.TenantSummary;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tells a browser which organization its host name addresses, so a page can show the organization's name before
 * anyone has signed in. The answer comes from the tenant context the filter derived from the host name; nothing the
 * client sends, apart from the host it connected to, can influence it.
 */
@RestController
@Tag(name = "Tenant")
class TenantController {

    private final TenantContexts contexts;
    private final Tenants tenants;

    TenantController(TenantContexts contexts, Tenants tenants) {
        this.contexts = contexts;
        this.tenants = tenants;
    }

    @GetMapping(ApiPaths.TENANT_CURRENT)
    @Operation(
            operationId = "getCurrentTenant",
            summary = "The organization of this host name",
            description = "Answers with the organization the request's host name addresses. A host that addresses no "
                    + "organization, or an unknown one, is NOT_FOUND; a suspended, deactivated or not yet open one "
                    + "is TENANT_UNAVAILABLE. The organization can never be chosen by a header, parameter or body.")
    ApiResponse<TenantSummary> current() {
        Tenant tenant = contexts.current()
                .flatMap(context -> tenants.findById(context.tenantId()))
                .orElseThrow(() -> ApiException.notFound("No organization exists at this address."));
        return ApiResponse.of(new TenantSummary(tenant.slug().value(), tenant.displayName()));
    }
}
