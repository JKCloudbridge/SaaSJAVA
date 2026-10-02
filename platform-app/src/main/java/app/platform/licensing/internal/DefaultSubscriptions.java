package app.platform.licensing.internal;

import app.platform.licensing.PoolView;
import app.platform.licensing.SubscriptionChange;
import app.platform.licensing.SubscriptionView;
import app.platform.licensing.Subscriptions;
import app.platform.sharedkernel.ActorId;
import app.platform.sharedkernel.TenantId;
import app.platformapi.ApiException;
import app.platformapi.ErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Subscriptions and trials (ADR-0033). A plan decides the pools: attaching a plan creates them, changing the plan
 * resizes
 * them, and a resize that would leave a pool below the licences in use is refused as a whole (nothing changes), so the
 * rule "a pool cannot be reduced below use" holds for plan changes too.
 */
@Service
class DefaultSubscriptions implements Subscriptions {

    private static final Set<String> STATUSES = Set.of("TRIAL", "ACTIVE", "SUSPENDED", "CANCELLED");

    private final LicensingStore store;
    private final OrganizationWork work;
    private final LicensingProperties properties;
    private final Clock clock;

    DefaultSubscriptions(LicensingStore store, OrganizationWork work, LicensingProperties properties, Clock clock) {
        this.store = store;
        this.work = work;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public SubscriptionView startDefault(TenantId tenant, ActorId actor) {
        return attach(tenant, properties.defaultPlan(), actor);
    }

    @Override
    public SubscriptionView attach(TenantId tenant, String planKey, ActorId actor) {
        LicensingStore.PlanRow plan = store.findPlan(planKey)
                .orElseThrow(() -> ApiException.notFound("This plan does not exist."));
        return work.in(tenant, () -> {
            if (store.subscription(tenant).isPresent()) {
                throw new ApiException(ErrorCode.CONFLICT, "This organization already has a subscription.");
            }
            Instant trialEnds = plan.trialDays() == null ? null
                    : clock.instant().plus(Duration.ofDays(plan.trialDays()));
            store.insertSubscription(tenant, plan.id(), trialEnds == null ? "ACTIVE" : "TRIAL", trialEnds, actor);
            resizePools(plan, actor);
            return store.subscription(tenant).orElseThrow().view();
        });
    }

    @Override
    public SubscriptionView change(TenantId tenant, SubscriptionChange change, ActorId actor) {
        if (change.status() != null && !STATUSES.contains(change.status())) {
            throw ApiException.validation("status", "Must be TRIAL, ACTIVE, SUSPENDED or CANCELLED.");
        }
        LicensingStore.PlanRow newPlan = change.planKey() == null ? null : store.findPlan(change.planKey())
                .orElseThrow(() -> ApiException.notFound("This plan does not exist."));
        return work.in(tenant, () -> {
            LicensingStore.SubscriptionRow current = store.subscription(tenant)
                    .orElseThrow(() -> ApiException.notFound("This organization has no subscription."));
            UUID planId = newPlan == null ? current.planId() : newPlan.id();
            String status = change.status() == null ? current.view().status() : change.status();
            Instant trialEnds = change.trialEndsAt() == null ? current.view().trialEndsAt() : change.trialEndsAt();
            Instant periodEnds = change.periodEndsAt() == null ? current.view().periodEndsAt()
                    : change.periodEndsAt();
            if ("TRIAL".equals(status) && trialEnds == null) {
                throw ApiException.validation("trialEndsAt", "A trial needs an end.");
            }
            if (newPlan != null && !newPlan.id().equals(current.planId())) {
                resizePools(newPlan, actor);
            }
            store.updateSubscription(current.id(), planId, status, trialEnds, periodEnds, actor);
            return store.subscription(tenant).orElseThrow().view();
        });
    }

    @Override
    public Optional<SubscriptionView> of(TenantId tenant) {
        return store.subscription(tenant).map(LicensingStore.SubscriptionRow::view);
    }

    @Override
    public Map<TenantId, SubscriptionView> of(Collection<TenantId> tenants) {
        return store.subscriptions(tenants);
    }

    /**
     * Makes the pools of the current organization match the plan: a pool of every licence type of the plan with the
     * plan's quantity, and no licences of a type the plan does not have. Runs in the organization's transaction.
     *
     * @throws ApiException {@code CONFLICT} when a pool would end up below the licences in use
     */
    private void resizePools(LicensingStore.PlanRow plan, ActorId actor) {
        Map<String, Integer> wanted = store.planLicences().getOrDefault(plan.id(), Map.of());
        Map<String, Integer> target = new java.util.HashMap<>(wanted);
        for (PoolView existing : store.pools()) {
            target.putIfAbsent(existing.licenceType(), 0);
        }
        for (Map.Entry<String, Integer> entry : target.entrySet()) {
            UUID typeId = store.licenceTypeId(entry.getKey()).orElseThrow();
            Optional<LicensingStore.PoolRow> pool = store.lockPool(typeId);
            if (pool.isEmpty()) {
                if (entry.getValue() > 0) {
                    store.insertPool(typeId, entry.getValue(), actor);
                }
                continue;
            }
            if (entry.getValue() < store.used(typeId)) {
                throw new ApiException(ErrorCode.CONFLICT,
                        "This plan would leave a pool below the licences in use. Release some licences first.");
            }
            if (entry.getValue() != pool.get().quantity()) {
                store.updatePool(pool.get().id(), entry.getValue(), actor);
            }
        }
    }
}
