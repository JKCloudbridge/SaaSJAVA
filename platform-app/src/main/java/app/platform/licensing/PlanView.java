package app.platform.licensing;

import java.util.Map;
import java.util.Set;

/**
 * A commercial plan (ADR-0031): default licence quantities and included features.
 *
 * @param key the stable lower-case key
 * @param name the name for people
 * @param trialDays how long a subscription on this plan starts as a trial, or null when it does not start as one
 * @param licences default quantity per licence type key
 * @param features keys of the features included by default
 */
public record PlanView(String key, String name, Integer trialDays, Map<String, Integer> licences,
        Set<String> features) {

    public PlanView {
        licences = Map.copyOf(licences);
        features = Set.copyOf(features);
    }
}
