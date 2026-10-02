package app.platform.licensing.internal;

import app.platform.licensing.FeatureView;
import app.platform.licensing.LicenceTypeView;
import app.platform.licensing.PlanView;
import app.platform.licensing.Plans;
import app.platform.sharedkernel.ActorId;
import app.platformapi.ApiException;
import app.platformapi.ErrorCode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** The catalogue: plans, licence types and feature keys (ADR-0031). Validates everything a platform person types. */
@Service
class DefaultPlans implements Plans {

    private static final Pattern KEY = Pattern.compile("[a-z][a-z0-9-]{0,39}");
    private static final int MAX_NAME = 80;
    private static final int MAX_QUANTITY = 100_000;

    private final LicensingStore store;
    private final TransactionTemplate transaction;

    DefaultPlans(LicensingStore store, TransactionTemplate transaction) {
        this.store = store;
        this.transaction = transaction;
    }

    @Override
    public List<PlanView> plans() {
        Map<UUID, Map<String, Integer>> licences = store.planLicences();
        Map<UUID, Set<String>> features = store.planFeatures();
        return store.plans().stream().map(plan -> view(plan, licences, features)).toList();
    }

    @Override
    public Optional<PlanView> plan(String key) {
        return store.findPlan(key).map(plan -> view(plan, store.planLicences(), store.planFeatures()));
    }

    @Override
    public PlanView save(String key, String name, Integer trialDays, Map<String, Integer> licences,
            Set<String> features, ActorId actor) {
        validKey("key", key);
        validName(name);
        if (trialDays != null && (trialDays < 1 || trialDays > 365)) {
            throw ApiException.validation("trialDays", "Must be between 1 and 365 days, or empty.");
        }
        for (Map.Entry<String, Integer> entry : licences.entrySet()) {
            if (store.licenceTypeId(entry.getKey()).isEmpty()) {
                throw ApiException.validation("licences", "Unknown licence type.");
            }
            if (entry.getValue() == null || entry.getValue() < 0 || entry.getValue() > MAX_QUANTITY) {
                throw ApiException.validation("licences", "A quantity must be between 0 and " + MAX_QUANTITY + ".");
            }
        }
        for (String feature : features) {
            if (store.featureId(feature).isEmpty()) {
                throw ApiException.validation("features", "Unknown feature.");
            }
        }
        try {
            transaction.executeWithoutResult(status -> {
                Optional<LicensingStore.PlanRow> existing = store.findPlan(key);
                UUID id;
                if (existing.isPresent()) {
                    id = existing.get().id();
                    store.updatePlan(id, name, trialDays, actor);
                } else {
                    id = store.insertPlan(key, name, trialDays, actor);
                }
                store.replacePlanLicences(id, licences, actor);
                store.replacePlanFeatures(id, features, actor);
            });
        } catch (DuplicateKeyException e) {
            // Two administrators created the same key at once: the unique index decided.
            throw new ApiException(ErrorCode.CONFLICT, "A plan with this key already exists.");
        }
        return plan(key).orElseThrow();
    }

    @Override
    public List<LicenceTypeView> licenceTypes() {
        return store.licenceTypes();
    }

    @Override
    public LicenceTypeView addLicenceType(String key, String name, ActorId actor) {
        validKey("key", key);
        validName(name);
        try {
            store.insertLicenceType(key, name, actor);
        } catch (DuplicateKeyException e) {
            throw new ApiException(ErrorCode.CONFLICT, "A licence type with this key already exists.");
        }
        return new LicenceTypeView(key, name);
    }

    @Override
    public List<FeatureView> features() {
        return store.features();
    }

    @Override
    public FeatureView addFeature(String key, String name, ActorId actor) {
        validKey("key", key);
        validName(name);
        try {
            store.insertFeature(key, name, actor);
        } catch (DuplicateKeyException e) {
            throw new ApiException(ErrorCode.CONFLICT, "A feature with this key already exists.");
        }
        return new FeatureView(key, name);
    }

    private static PlanView view(LicensingStore.PlanRow plan, Map<UUID, Map<String, Integer>> licences,
            Map<UUID, Set<String>> features) {
        return new PlanView(plan.key(), plan.name(), plan.trialDays(),
                licences.getOrDefault(plan.id(), Map.of()), features.getOrDefault(plan.id(), Set.of()));
    }

    private static void validKey(String field, String key) {
        if (key == null || !KEY.matcher(key).matches()) {
            throw ApiException.validation(field,
                    "Use 1 to 40 lower-case letters, digits or hyphens, starting with a letter.");
        }
    }

    private static void validName(String name) {
        if (name == null || name.isBlank() || name.length() > MAX_NAME || !name.equals(name.strip())) {
            throw ApiException.validation("name", "Must have 1 to " + MAX_NAME + " characters, without leading or "
                    + "trailing spaces.");
        }
    }
}
