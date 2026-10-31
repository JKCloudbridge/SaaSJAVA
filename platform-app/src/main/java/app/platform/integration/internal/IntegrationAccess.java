package app.platform.integration.internal;

import app.platform.licensing.Entitlements;
import app.platform.security.Ability;
import app.platform.security.Permissions;
import app.platform.tenant.TenantContext;
import app.platform.tenant.TenantContexts;
import app.platformapi.ApiException;
import app.platformapi.ErrorCode;
import org.springframework.stereotype.Component;

/**
 * Who may reach the integrations of an organization, in one place (ADR-0209). Three separate mechanisms, asked in
 * this order and never merged: the caller must be a member of the organization the host names (a platform person who
 * is not a member is not found, and gets no door through support access either, because integration settings are
 * never read on behalf of the platform), then the ability, then the plan feature.
 */
@Component
class IntegrationAccess {

    static final String FEATURE = "integrations";

    private final TenantContexts contexts;
    private final Permissions permissions;
    private final Entitlements entitlements;

    IntegrationAccess(TenantContexts contexts, Permissions permissions, Entitlements entitlements) {
        this.contexts = contexts;
        this.permissions = permissions;
        this.entitlements = entitlements;
    }

    /** Whether the caller, a member, holds the ability: for what a screen may offer, never a gate. */
    boolean can(Ability ability) {
        return contexts.current().filter(context -> context.membershipId() != null)
                .map(caller -> permissions.has(caller.membershipId(), ability)).orElse(false);
    }

    /** The caller, once they hold every one of the abilities and the organization has the feature. */
    TenantContext require(Ability... abilities) {
        TenantContext caller = contexts.current().filter(context -> context.membershipId() != null)
                .orElseThrow(() -> ApiException.notFound("This page belongs to an organization."));
        for (Ability ability : abilities) {
            if (!permissions.has(caller.membershipId(), ability)) {
                throw new ApiException(ErrorCode.FORBIDDEN);
            }
        }
        if (!entitlements.enabled(FEATURE)) {
            throw new ApiException(ErrorCode.CONFLICT, "Integrations are not part of this organization's plan.");
        }
        return caller;
    }
}
