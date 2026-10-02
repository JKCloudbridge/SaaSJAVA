package app.platform.licensing.internal;

import app.platform.licensing.EntitlementView;
import app.platform.licensing.Entitlements;
import app.platform.licensing.FeatureView;
import app.platform.sharedkernel.ActorId;
import app.platform.sharedkernel.TenantId;
import app.platformapi.ApiException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Feature entitlements (ADR-0034): the plan's features while the subscription is a trial or active, then the explicit
 * switch of a platform administrator for that organization over it. Nothing is cached.
 */
@Service
class DefaultEntitlements implements Entitlements {

    private final LicensingStore store;
    private final OrganizationWork work;
    private final TransactionTemplate transaction;

    DefaultEntitlements(LicensingStore store, OrganizationWork work, TransactionTemplate transaction) {
        this.store = store;
        this.work = work;
        this.transaction = transaction;
    }

    @Override
    public boolean enabled(String feature) {
        return enabled(work.current(), feature);
    }

    @Override
    public boolean enabled(TenantId tenant, String feature) {
        Boolean override = store.overrides(tenant).get(feature);
        if (override != null) {
            return override;
        }
        return planFeatures(tenant).contains(feature);
    }

    @Override
    public List<EntitlementView> of(TenantId tenant) {
        Set<String> inPlan = planFeatures(tenant);
        Map<String, Boolean> overrides = store.overrides(tenant);
        return store.features().stream().map(feature -> view(feature, inPlan, overrides)).toList();
    }

    @Override
    public void override(TenantId tenant, String feature, Boolean enabled, ActorId actor) {
        UUID featureId = store.featureId(feature)
                .orElseThrow(() -> ApiException.notFound("This feature does not exist."));
        transaction.executeWithoutResult(status -> {
            if (enabled == null) {
                store.removeOverride(tenant, featureId, actor);
            } else {
                store.upsertOverride(tenant, featureId, enabled, actor);
            }
        });
    }

    private Set<String> planFeatures(TenantId tenant) {
        Optional<LicensingStore.SubscriptionRow> subscription = store.subscription(tenant);
        if (subscription.isEmpty()) {
            return Set.of();
        }
        String status = subscription.get().view().status();
        if (!"TRIAL".equals(status) && !"ACTIVE".equals(status)) {
            return Set.of();
        }
        return store.planFeatures().getOrDefault(subscription.get().planId(), Set.of());
    }

    private static EntitlementView view(FeatureView feature, Set<String> inPlan, Map<String, Boolean> overrides) {
        Boolean override = overrides.get(feature.key());
        boolean plan = inPlan.contains(feature.key());
        return new EntitlementView(feature.key(), feature.name(), plan, override,
                override != null ? override : plan);
    }
}
