package app.platform.tenant;

import app.platform.sharedkernel.ActorId;
import app.platform.sharedkernel.TenantId;
import java.util.List;
import java.util.Optional;

/**
 * Creates organizations and moves them through their life (ADR-0014). The only way other modules touch tenants.
 *
 * <p>Every operation that changes a tenant runs in one database transaction together with the event that announces
 * it ({@code tenant.provisioned}, {@code tenant.activated}, {@code tenant.suspended}, {@code tenant.reinstated},
 * {@code tenant.deactivated}); the event reaches the outbox only if the change was committed (ADR-0016). The
 * tenant context of the operation is the tenant it changes, opened by the service itself: a caller that already runs
 * a transaction must have opened the same tenant context before starting it (see {@link TenantContexts}).
 *
 * <p>The service performs no authorization. Its callers decide who may call: founding an organization (Sprint 4)
 * and the platform console (Sprint 6, platform administrators only, ADR-0038).
 */
public interface Tenants {

    /** A new identifier for a tenant, for a caller that must know it before the tenant exists. */
    TenantId newId();

    /**
     * Creates an organization in the {@link TenantStatus#PROVISIONING} state.
     *
     * @param slug the host name label; use {@link TenantSlug#of(String)} for user input
     * @param displayName the name for people, 1 to 200 characters without leading or trailing spaces
     * @param actor who is creating it
     * @return the new tenant
     * @throws app.platformapi.ApiException {@code VALIDATION_ERROR} for a bad name, {@code CONFLICT} when the slug is
     *         taken
     */
    Tenant provision(TenantSlug slug, String displayName, ActorId actor);

    /**
     * Like {@link #provision(TenantSlug, String, ActorId)} with an identifier chosen by the caller, taken from
     * {@link #newId()}. The caller may already have opened that tenant's context and a transaction.
     */
    Tenant provision(TenantId id, TenantSlug slug, String displayName, ActorId actor);

    /** PROVISIONING to ACTIVE: the organization opens. */
    Tenant activate(TenantId id, ActorId actor);

    /** ACTIVE to SUSPENDED: the organization is closed temporarily. */
    Tenant suspend(TenantId id, ActorId actor);

    /** SUSPENDED to ACTIVE: the organization is open again. */
    Tenant reinstate(TenantId id, ActorId actor);

    /** Any state to DEACTIVATED: the organization is closed for good. */
    Tenant deactivate(TenantId id, ActorId actor);

    /** The tenant with this identifier, whatever its status. */
    Optional<Tenant> findById(TenantId id);

    /** The tenant with this slug, whatever its status. */
    Optional<Tenant> findBySlug(TenantSlug slug);

    /**
     * One page of organizations, ordered by short name, for platform administration (the caller checks who may ask).
     *
     * @param afterSlug only organizations whose short name sorts after this one (the previous page), or null
     * @param search only organizations whose name or short name contains this text, or null
     * @param limit the page size
     */
    List<Tenant> list(String afterSlug, String search, int limit);

    /**
     * How many organizations that are not deactivated were provisioned by this actor. Lets the code that lets a person
     * found organizations limit how many one person holds; the count cannot come from the memberships, because they
     * are isolated per tenant.
     */
    long countFoundedBy(ActorId actor);
}
