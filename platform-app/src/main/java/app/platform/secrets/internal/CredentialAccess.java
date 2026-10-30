package app.platform.secrets.internal;

import app.platform.licensing.Entitlements;
import app.platform.security.Ability;
import app.platform.security.Permissions;
import app.platform.tenant.TenantContext;
import app.platform.tenant.TenantContexts;
import app.platformapi.ApiException;
import app.platformapi.ErrorCode;
import org.springframework.stereotype.Component;

/**
 * Who may reach the credentials of an organization, in one place (ADR-0213). Three separate mechanisms, asked in this
 * order and never merged: the caller must be a member of the organization the host names (a platform person who is not
 * a member is not found, and support access gives no door: credentials are never read or written on behalf of the
 * platform), then the ability, then the plan feature. The plan feature is the one of the connections that use
 * credentials.
 */
@Component
class CredentialAccess {

    static final String FEATURE = "integrations";

    private final TenantContexts contexts;
    private final Permissions permissions;
    private final Entitlements entitlements;

    CredentialAccess(TenantContexts contexts, Permissions permissions, Entitlements entitlements) {
        this.contexts = contexts;
        this.permissions = permissions;
        this.entitlements = entitlements;
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
            throw new ApiException(ErrorCode.CONFLICT, "Credentials are not part of this organization's plan.");
        }
        return caller;
    }
}
