/**
 * Tenant module: organizations, tenant lifecycle, tenant context, hostname resolution (ADR-0014, ADR-0017).
 *
 * <p>Other modules may use only the types in this package (its public API): {@code Tenants},
 * {@code TenantContexts}, {@code TenantContext}, {@code Tenant}, {@code TenantStatus}, {@code TenantSlug} and
 * {@code SystemScope}. Everything in sub-packages is internal. Allowed outgoing dependencies are declared below and
 * verified by the architecture tests.
 */
@ApplicationModule(
        displayName = "Tenant",
        allowedDependencies = {
            "sharedkernel"
        })
package app.platform.tenant;

import org.springframework.modulith.ApplicationModule;
