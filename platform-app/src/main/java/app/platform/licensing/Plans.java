package app.platform.licensing;

import app.platform.sharedkernel.ActorId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The catalogue the vendor sells from (ADR-0031): plans, licence types and feature keys. Platform-level data, the same
 * for every organization; only platform administrators change it (the caller decides who may).
 */
public interface Plans {

    /** Every plan, by key. */
    List<PlanView> plans();

    /** The plan with this key. */
    Optional<PlanView> plan(String key);

    /**
     * Creates the plan, or replaces the one with this key (name, trial days, quantities and features). Existing
     * organizations keep their pools until their subscription changes plan.
     *
     * @param licences default quantity per licence type key
     * @param features feature keys
     * @throws app.platformapi.ApiException {@code VALIDATION_ERROR} for a malformed key or name, an unknown licence
     *         type
     *         or feature, or a quantity out of range
     */
    PlanView save(String key, String name, Integer trialDays, Map<String, Integer> licences, Set<String> features,
            ActorId actor);

    /** Every licence type. */
    List<LicenceTypeView> licenceTypes();

    /**
     * Adds a licence type of the kind ({@code SEAT} or {@code ADD_ON}, see {@link LicenceTypeView}).
     *
     * @throws app.platformapi.ApiException {@code VALIDATION_ERROR} or {@code CONFLICT}
     */
    LicenceTypeView addLicenceType(String key, String name, String kind, ActorId actor);

    /** Every feature key. */
    List<FeatureView> features();

    /** Adds a feature key. @throws app.platformapi.ApiException {@code VALIDATION_ERROR} or {@code CONFLICT} */
    FeatureView addFeature(String key, String name, ActorId actor);
}
