package app.platform.platformadmin.internal;

import app.platform.sharedkernel.ActorId;
import app.platform.sharedkernel.TenantId;
import app.platform.sharedkernel.limits.RateLimitSettings;
import app.platform.sharedkernel.limits.RateLimitSettings.Limits;
import app.platform.tenant.Tenant;
import app.platform.tenant.Tenants;
import app.platformapi.ApiException;
import app.platformapi.OrganizationLimitsView;
import app.platformapi.SetOrganizationLimitsRequest;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * The limits on the public API of one organization, for the platform console (Sprint 32, ADR-0283). A platform person
 * reaches the organization the way every platform action does, by opening that organization's tenant context before a
 * transaction (no privileged connection), and changes only the numbers: they read no record, member or setting of the
 * organization. The reason is bounded, kept in the audit trail only and never logged, mailed or shown to the
 * organization. A platform role gives no webhook ability and no ability to use the public API: this changes how many
 * requests an organization may make, never what any of them may do.
 */
@Service
class PlatformLimitsService {

    private final Tenants tenants;
    private final OrganizationScope scope;
    private final RateLimitSettings settings;
    private final PlatformAudit audit;

    PlatformLimitsService(Tenants tenants, OrganizationScope scope, RateLimitSettings settings, PlatformAudit audit) {
        this.tenants = tenants;
        this.scope = scope;
        this.settings = settings;
        this.audit = audit;
    }

    OrganizationLimitsView read(UUID organizationId) {
        Tenant tenant = find(organizationId);
        // The platform person is the actor of the read; the organization's context only limits the statements.
        return scope.in(tenant.id(), ActorId.SYSTEM.value(), () -> view(tenant.id()));
    }

    OrganizationLimitsView set(UUID actor, UUID organizationId, SetOrganizationLimitsRequest request) {
        Tenant tenant = find(organizationId);
        return scope.in(tenant.id(), actor, () -> {
            try {
                settings.set(request.organizationPerMinute(), request.organizationPerDay(),
                        request.applicationPerMinute(), actor);
            } catch (IllegalArgumentException refused) {
                throw ApiException.validation("organizationPerMinute", refused.getMessage());
            }
            audit.done("limits.override_set", actor, tenant.id(), request.reason(), "organization",
                    tenant.id().toString());
            return view(tenant.id());
        });
    }

    OrganizationLimitsView clear(UUID actor, UUID organizationId, String reason) {
        Tenant tenant = find(organizationId);
        return scope.in(tenant.id(), actor, () -> {
            boolean removed = settings.clear(actor);
            if (removed) {
                audit.done("limits.override_removed", actor, tenant.id(), reason, "organization",
                        tenant.id().toString());
            }
            return view(tenant.id());
        });
    }

    private OrganizationLimitsView view(TenantId organization) {
        Limits now = settings.effective();
        Limits base = settings.defaults();
        return new OrganizationLimitsView(organization.value(), now.overridden(), now.organizationPerMinute(),
                now.organizationPerDay(), now.applicationPerMinute(), base.organizationPerMinute(),
                base.organizationPerDay(), base.applicationPerMinute());
    }

    private Tenant find(UUID organizationId) {
        return tenants.findById(new TenantId(organizationId))
                .orElseThrow(() -> ApiException.notFound("The organization was not found."));
    }
}
