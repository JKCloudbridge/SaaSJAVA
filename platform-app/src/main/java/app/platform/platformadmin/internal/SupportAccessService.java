package app.platform.platformadmin.internal;

import app.platform.identity.OrganizationAdministration;
import app.platform.sharedkernel.TenantId;
import app.platform.sharedkernel.support.SupportAccess;
import app.platform.sharedkernel.support.SupportAccessDeniedException;
import app.platform.tenant.Tenant;
import app.platform.tenant.TenantContexts;
import app.platform.tenant.TenantStatus;
import app.platform.tenant.Tenants;
import app.platformapi.ApiException;
import app.platformapi.ErrorCode;
import app.platformapi.SupportAccessView;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * Controlled support access (ADR-0035): request, approval, time limit, audit; no automatic access for platform support.
 *
 * <p>A platform person asks for one organization; an administrator of that organization approves or denies from its
 * own screens; the approval opens a window of at most four hours (the database refuses a longer one); the organization
 * may revoke at any time; the window ends by itself. {@link #require} is the one enforcement point every later read of
 * an organization's data on behalf of a platform person must pass. Every step is audited, with the platform person
 * and the organization.
 */
@Service
class SupportAccessService implements SupportAccess {

    static final String NO_LONGER_OPEN = "This request is no longer open.";

    private final SupportAccessRepository grants;
    private final OrganizationScope scope;
    private final OrganizationAdministration administration;
    private final Tenants tenants;
    private final TenantContexts contexts;
    private final PlatformAudit audit;

    SupportAccessService(SupportAccessRepository grants, OrganizationScope scope,
            OrganizationAdministration administration, Tenants tenants, TenantContexts contexts,
            PlatformAudit audit) {
        this.grants = grants;
        this.scope = scope;
        this.administration = administration;
        this.tenants = tenants;
        this.contexts = contexts;
        this.audit = audit;
    }

    // ---- the platform person's side ----

    /** Asks the organization for access. One open request per person and organization. */
    void request(UUID actor, UUID organizationId, String reason, int minutes) {
        Tenant tenant = tenants.findById(new TenantId(organizationId))
                .orElseThrow(() -> ApiException.notFound("The organization was not found."));
        if (tenant.status() != TenantStatus.ACTIVE) {
            throw new ApiException(ErrorCode.CONFLICT, "The organization is not open.");
        }
        try {
            scope.in(tenant.id(), actor, () -> {
                grants.cancelStale(actor);
                UUID id = grants.insertRequest(actor, reason, minutes);
                audit.done("platform.support_access.requested", actor, tenant.id(), reason, "grant", id.toString());
                return null;
            });
        } catch (DuplicateKeyException e) {
            throw new ApiException(ErrorCode.CONFLICT, "You already have an open request for this organization.");
        }
    }

    /** The grants of an organization, for platform administrators and support. */
    List<SupportAccessView> listFor(UUID organizationId) {
        Tenant tenant = tenants.findById(new TenantId(organizationId))
                .orElseThrow(() -> ApiException.notFound("The organization was not found."));
        return scope.in(tenant.id(), null, () -> grants.list().stream().map(SupportAccessService::view).toList());
    }

    /** The person withdraws their own open request. */
    void cancel(UUID actor, UUID organizationId, UUID grantId) {
        Tenant tenant = tenants.findById(new TenantId(organizationId))
                .orElseThrow(() -> ApiException.notFound("The organization was not found."));
        scope.in(tenant.id(), actor, () -> {
            SupportAccessRepository.Grant grant = grants.findForUpdate(grantId)
                    .filter(found -> found.requestedBy().equals(actor))
                    .orElseThrow(() -> ApiException.notFound("This request does not exist."));
            if (!"REQUESTED".equals(grant.status()) || grant.ended()) {
                throw new ApiException(ErrorCode.CONFLICT, NO_LONGER_OPEN);
            }
            grants.cancel(grantId, actor);
            audit.done("platform.support_access.cancelled", actor, tenant.id(), null, "grant", grantId.toString());
            return null;
        });
    }

    // ---- the organization's side (an administrator of the organization of the host) ----

    List<SupportAccessView> list() {
        return administration.asAdministrator("support_access.list", caller ->
                grants.list().stream().map(SupportAccessService::view).toList());
    }

    /**
     * Approves a request: the access window opens now and lasts at most as long as was asked (and four hours).
     *
     * @param minutes how long, or null for as asked
     */
    void approve(UUID grantId, Integer minutes) {
        administration.asAdministrator("support_access.approve", caller -> {
            SupportAccessRepository.Grant grant = open(grantId);
            int window = minutes == null ? grant.requestedMinutes() : minutes;
            if (window < 15 || window > grant.requestedMinutes()) {
                throw ApiException.validation("minutes", "Must be between 15 and the " + grant.requestedMinutes()
                        + " minutes that were asked for.");
            }
            try {
                grants.approve(grantId, caller.userId(), window);
            } catch (DataIntegrityViolationException e) {
                // The database refuses a window longer than four hours; no driver text is kept.
                throw ApiException.validation("minutes", "Must be at most 240 minutes.");
            }
            audit.done("support_access.grant.approved", caller.userId(), contexts.require().tenantId(), null, "grant",
                    grantId.toString());
            return null;
        });
    }

    void deny(UUID grantId) {
        administration.asAdministrator("support_access.deny", caller -> {
            open(grantId);
            grants.deny(grantId, caller.userId());
            audit.done("support_access.grant.denied", caller.userId(), contexts.require().tenantId(), null, "grant",
                    grantId.toString());
            return null;
        });
    }

    /** Ends an approved access at once. */
    void revoke(UUID grantId) {
        administration.asAdministrator("support_access.revoke", caller -> {
            SupportAccessRepository.Grant grant = grants.findForUpdate(grantId)
                    .orElseThrow(() -> ApiException.notFound("This request does not exist."));
            if (!"APPROVED".equals(grant.status())) {
                throw new ApiException(ErrorCode.CONFLICT, "This access is not approved.");
            }
            grants.revoke(grantId, caller.userId());
            audit.done("support_access.grant.revoked", caller.userId(), contexts.require().tenantId(), null, "grant",
                    grantId.toString());
            return null;
        });
    }

    // ---- the enforcement point ----

    @Override
    public void require(TenantId organization, UUID platformUser) {
        boolean allowed = scope.in(organization, platformUser, () -> grants.hasActive(platformUser));
        if (!allowed) {
            audit.refused("platform.support_access.refused", platformUser, organization, "no_active_grant");
            throw new SupportAccessDeniedException();
        }
        audit.done("platform.support_access.used", platformUser, organization, null);
    }

    // ---- helpers ----

    /** A request of the current organization that can still be answered. */
    private SupportAccessRepository.Grant open(UUID grantId) {
        SupportAccessRepository.Grant grant = grants.findForUpdate(grantId)
                .orElseThrow(() -> ApiException.notFound("This request does not exist."));
        if (!"REQUESTED".equals(grant.status()) || grant.ended()) {
            throw new ApiException(ErrorCode.CONFLICT, NO_LONGER_OPEN);
        }
        return grant;
    }

    private static SupportAccessView view(SupportAccessRepository.Grant grant) {
        String status = grant.ended() ? "EXPIRED" : grant.status();
        return new SupportAccessView(grant.id(), grant.requester(), grant.reason(), status,
                grant.requestedMinutes(), grant.requestedAt(), grant.accessExpiresAt(), grant.active());
    }
}
