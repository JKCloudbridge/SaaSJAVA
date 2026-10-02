package app.platform.licensing;

import app.platform.sharedkernel.ActorId;
import app.platform.sharedkernel.TenantId;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * Which plan an organization is on and where its subscription stands (ADR-0033). Platform-level records, so the console
 * can list them across organizations; attaching a plan also creates the organization's pools, which are tenant-scoped,
 * so the methods that change a subscription open that one organization's context themselves (the caller must not be
 * inside a transaction of another tenant).
 *
 * <p>No payment processor is coupled, and nothing here ends a trial by itself: a trial that is over is reported as
 * expired and a platform administrator decides (the scheduler of Sprint 25 will automate it).
 */
public interface Subscriptions {

    /**
     * "Try for free": puts a new organization on the default plan, as a trial when the plan has trial days, and creates
     * its pools from the plan.
     */
    SubscriptionView startDefault(TenantId tenant, ActorId actor);

    /**
     * Puts an organization that has no subscription on a plan chosen by a platform administrator (a trial when the plan
     * has trial days, otherwise active), and creates its pools.
     *
     * @throws app.platformapi.ApiException {@code NOT_FOUND} for an unknown plan, {@code CONFLICT} when the
     *         organization
     *         already has a subscription
     */
    SubscriptionView attach(TenantId tenant, String planKey, ActorId actor);

    /**
     * Changes the plan, the status or the dates of an existing subscription.
     *
     * @throws app.platformapi.ApiException {@code NOT_FOUND} for an unknown plan or no subscription,
     *         {@code VALIDATION_ERROR} for an unknown status or a trial without an end, {@code CONFLICT} when a new
     * plan
     *         would reduce a pool below the licences in use
     */
    SubscriptionView change(TenantId tenant, SubscriptionChange change, ActorId actor);

    /** The organization's subscription, if it has one. */
    Optional<SubscriptionView> of(TenantId tenant);

    /** The subscriptions of several organizations at once (for a list); organizations without one are absent. */
    Map<TenantId, SubscriptionView> of(Collection<TenantId> tenants);
}
