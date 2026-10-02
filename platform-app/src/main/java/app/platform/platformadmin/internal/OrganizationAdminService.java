package app.platform.platformadmin.internal;

import app.platform.identity.Invitations;
import app.platform.identity.SessionAdministration;
import app.platform.licensing.EntitlementView;
import app.platform.licensing.Entitlements;
import app.platform.licensing.Licences;
import app.platform.licensing.Plans;
import app.platform.licensing.PoolView;
import app.platform.licensing.SubscriptionChange;
import app.platform.licensing.SubscriptionView;
import app.platform.licensing.Subscriptions;
import app.platform.sharedkernel.ActorId;
import app.platform.sharedkernel.TenantId;
import app.platform.tenant.Tenant;
import app.platform.tenant.TenantSlug;
import app.platform.tenant.Tenants;
import app.platformapi.ApiException;
import app.platformapi.ApiPageResponse;
import app.platformapi.ChangeSubscriptionRequest;
import app.platformapi.Cursors;
import app.platformapi.EntitlementInfo;
import app.platformapi.ErrorCode;
import app.platformapi.FirstAdministratorInfo;
import app.platformapi.LicencePoolView;
import app.platformapi.PageRequest;
import app.platformapi.Pagination;
import app.platformapi.PlatformOrganizationDetail;
import app.platformapi.PlatformOrganizationSummary;
import app.platformapi.ProvisionOrganizationRequest;
import app.platformapi.SetEntitlementRequest;
import app.platformapi.SetPoolRequest;
import app.platformapi.SubscriptionInfo;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * What a platform person does with organizations (ADR-0031, ADR-0037, ADR-0038): list and look at them, set one up for
 * a client, suspend, reinstate and close them, change their subscription, pools and features, and sign everybody out.
 *
 * <p>Every action that changes something runs in one transaction under that one organization's context (so row level
 * security limits it to the organization) together with its audit record, and the record names the platform person, the
 * target organization and a bounded reason. The role of the caller is checked by the controller before anything here
 * runs; nothing here reads a member list or business data of an organization.
 */
@Service
class OrganizationAdminService {

    private final Tenants tenants;
    private final Subscriptions subscriptions;
    private final Entitlements entitlements;
    private final Licences licences;
    private final Plans plans;
    private final Invitations invitations;
    private final SessionAdministration sessions;
    private final OrganizationScope scope;
    private final PlatformAudit audit;
    private final Clock clock;

    OrganizationAdminService(Tenants tenants, Subscriptions subscriptions, Entitlements entitlements,
            Licences licences, Plans plans, Invitations invitations,
            SessionAdministration sessions, OrganizationScope scope, PlatformAudit audit, Clock clock) {
        this.tenants = tenants;
        this.subscriptions = subscriptions;
        this.entitlements = entitlements;
        this.licences = licences;
        this.plans = plans;
        this.invitations = invitations;
        this.sessions = sessions;
        this.scope = scope;
        this.audit = audit;
        this.clock = clock;
    }

    // ---- reading ----

    ApiPageResponse<PlatformOrganizationSummary> list(PageRequest page, String search) {
        String after = Cursors.decode(page.cursor()).orElse(null);
        List<Tenant> found = tenants.list(after, search, page.limit() + 1);
        boolean more = found.size() > page.limit();
        List<Tenant> shown = more ? found.subList(0, page.limit()) : found;
        Map<TenantId, SubscriptionView> subscribed = subscriptions.of(shown.stream().map(Tenant::id).toList());
        Instant now = clock.instant();
        List<PlatformOrganizationSummary> items = shown.stream()
                .map(tenant -> summary(tenant, subscribed.get(tenant.id()), now)).toList();
        Pagination pagination = more
                ? Pagination.more(page.limit(), Cursors.encode(shown.get(shown.size() - 1).slug().value()))
                : Pagination.last(page.limit());
        return ApiPageResponse.of(items, pagination);
    }

    PlatformOrganizationDetail detail(UUID organizationId) {
        Tenant tenant = find(organizationId);
        Instant now = clock.instant();
        Optional<SubscriptionView> subscription = subscriptions.of(tenant.id());
        List<PoolView> pools = scope.open(tenant.id(), null, licences::pools);
        List<EntitlementView> features = entitlements.of(tenant.id());
        Optional<Invitations.FirstAdministratorInvitation> first =
                invitations.firstAdministratorInvitation(tenant.id());
        return new PlatformOrganizationDetail(tenant.id().value(), tenant.slug().value(), tenant.displayName(),
                tenant.status().name(), tenant.statusChangedAt(), subscription.map(s -> info(s, now)).orElse(null),
                pools.stream().map(pool -> new LicencePoolView(pool.licenceType(), pool.name(), pool.quantity(),
                        pool.assigned(), pool.available())).toList(),
                features.stream().map(f -> new EntitlementInfo(f.key(), f.name(), f.inPlan(), f.override(),
                        f.enabled())).toList(),
                first.map(f -> new FirstAdministratorInfo(f.id(), f.status(), f.expiresAt(), f.sentCount()))
                        .orElse(null));
    }

    // ---- provisioning ----

    /**
     * Sets up an organization for a client: the organization (closed, PROVISIONING), its plan and pools, and the
     * invitation of its first administrator, all in one transaction. The answer is the same whether or not the address
     * has an account: the invitation request looks at no account, and the mail decides later.
     */
    PlatformOrganizationSummary provision(UUID actor, ProvisionOrganizationRequest request) {
        TenantSlug slug = TenantSlug.of(request.slug());
        if (plans.plan(request.planKey()).isEmpty()) {
            throw ApiException.validation("planKey", "This plan does not exist.");
        }
        if (tenants.findBySlug(slug).isPresent()) {
            throw ApiException.validation("slug", "Is not available.");
        }
        TenantId id = tenants.newId();
        ActorId by = new ActorId(actor);
        try {
            scope.in(id, actor, () -> {
                tenants.provision(id, slug, request.displayName(), by);
                subscriptions.attach(id, request.planKey(), by);
                invitations.inviteFirstAdministrator(id, request.email(), actor);
                audit.done("platform.organization.provisioned", actor, id, null, "slug", slug.value());
                return null;
            });
        } catch (ApiException e) {
            if (e.code() == ErrorCode.CONFLICT) {
                // Another request took the name between the check and the insert; the unique index decided.
                throw ApiException.validation("slug", "Is not available.");
            }
            throw e;
        }
        Tenant created = tenants.findById(id).orElseThrow();
        return summary(created, subscriptions.of(id).orElse(null), clock.instant());
    }

    /** Invites a new first administrator (the organization is being set up, or lost every administrator). */
    void inviteFirstAdministrator(UUID actor, UUID organizationId, String email) {
        Tenant tenant = find(organizationId);
        invitations.inviteFirstAdministrator(tenant.id(), email, actor);
    }

    /** Sends the open first-administrator invitation again. */
    void resendFirstAdministrator(UUID actor, UUID organizationId) {
        Tenant tenant = find(organizationId);
        invitations.resendFirstAdministrator(tenant.id(), actor);
    }

    // ---- lifecycle ----

    void suspend(UUID actor, UUID organizationId, String reason) {
        Tenant tenant = find(organizationId);
        scope.in(tenant.id(), actor, () -> {
            tenants.suspend(tenant.id(), new ActorId(actor));
            // The host answers "not available" at once; the sessions end too, so reinstating needs a new sign-in.
            sessions.signOutOrganization(tenant.id(), actor, null);
            audit.done("platform.organization.suspended", actor, tenant.id(), reason);
            return null;
        });
    }

    void reinstate(UUID actor, UUID organizationId, String reason) {
        Tenant tenant = find(organizationId);
        scope.in(tenant.id(), actor, () -> {
            tenants.reinstate(tenant.id(), new ActorId(actor));
            audit.done("platform.organization.reinstated", actor, tenant.id(), reason);
            return null;
        });
    }

    /** Closes an organization for good (also cancels one that was still being set up). */
    void deactivate(UUID actor, UUID organizationId, String reason, String confirm) {
        Tenant tenant = find(organizationId);
        if (confirm == null || !confirm.equals(tenant.slug().value())) {
            throw ApiException.validation("confirm", "Type the short name of the organization to confirm.");
        }
        scope.in(tenant.id(), actor, () -> {
            int revoked = invitations.revokeOpenInvitations(tenant.id(), actor);
            tenants.deactivate(tenant.id(), new ActorId(actor));
            sessions.signOutOrganization(tenant.id(), actor, null);
            audit.done("platform.organization.deactivated", actor, tenant.id(), reason, "invitations_revoked",
                    Integer.toString(revoked));
            return null;
        });
    }

    // ---- subscription, pools, entitlements, sessions ----

    PlatformOrganizationDetail changeSubscription(UUID actor, UUID organizationId, ChangeSubscriptionRequest request) {
        Tenant tenant = find(organizationId);
        ActorId by = new ActorId(actor);
        scope.in(tenant.id(), actor, () -> {
            if (subscriptions.of(tenant.id()).isEmpty()) {
                if (request.planKey() == null) {
                    throw ApiException.validation("planKey", "This organization has no plan yet: choose one.");
                }
                subscriptions.attach(tenant.id(), request.planKey(), by);
            } else {
                subscriptions.change(tenant.id(), new SubscriptionChange(request.planKey(), request.status(),
                        request.trialEndsAt(), request.periodEndsAt()), by);
            }
            audit.done("platform.subscription.changed", actor, tenant.id(), request.reason());
            return null;
        });
        return detail(organizationId);
    }

    PlatformOrganizationDetail setPool(UUID actor, UUID organizationId, String licenceType, SetPoolRequest request) {
        Tenant tenant = find(organizationId);
        scope.in(tenant.id(), actor, () -> {
            licences.setPoolQuantity(licenceType, request.quantity(), new ActorId(actor));
            audit.done("platform.licence_pool.changed", actor, tenant.id(), request.reason(), "licence_type",
                    licenceType);
            return null;
        });
        return detail(organizationId);
    }

    PlatformOrganizationDetail setEntitlement(UUID actor, UUID organizationId, String feature,
            SetEntitlementRequest request) {
        Tenant tenant = find(organizationId);
        scope.in(tenant.id(), actor, () -> {
            entitlements.override(tenant.id(), feature, request.enabled(), new ActorId(actor));
            audit.done("platform.entitlement.changed", actor, tenant.id(), request.reason(), "feature", feature);
            return null;
        });
        return detail(organizationId);
    }

    void signOutOrganization(UUID actor, UUID organizationId, String reason) {
        Tenant tenant = find(organizationId);
        scope.in(tenant.id(), actor, () -> {
            int ended = sessions.signOutOrganization(tenant.id(), actor, null);
            audit.done("platform.organization.signed_out_all", actor, tenant.id(), reason, "count",
                    Integer.toString(ended));
            return null;
        });
    }

    // ---- helpers ----

    private Tenant find(UUID organizationId) {
        return tenants.findById(new TenantId(organizationId))
                .orElseThrow(() -> ApiException.notFound("The organization was not found."));
    }

    private static PlatformOrganizationSummary summary(Tenant tenant, SubscriptionView subscription, Instant now) {
        return new PlatformOrganizationSummary(tenant.id().value(), tenant.slug().value(), tenant.displayName(),
                tenant.status().name(), subscription == null ? null : subscription.planName(),
                subscription == null ? null : subscription.status(),
                subscription == null ? null : subscription.trialEndsAt(),
                subscription != null && subscription.trialExpired(now));
    }

    private static SubscriptionInfo info(SubscriptionView subscription, Instant now) {
        return new SubscriptionInfo(subscription.planKey(), subscription.planName(), subscription.status(),
                subscription.startedAt(), subscription.trialEndsAt(), subscription.periodEndsAt(),
                subscription.trialExpired(now));
    }
}
