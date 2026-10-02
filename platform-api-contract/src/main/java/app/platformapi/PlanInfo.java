package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;

/**
 * A plan of the catalogue.
 *
 * @param key the plan key
 * @param name the plan name for people
 * @param trialDays how many days a subscription on it starts as a trial, absent when it does not
 * @param licences the default quantity per licence type key
 * @param features the keys of the features it includes
 */
public record PlanInfo(@NotNull String key, @NotNull String name, Integer trialDays,
        @NotNull Map<String, Integer> licences, @NotNull List<String> features) {

    public PlanInfo {
        licences = Map.copyOf(licences);
        features = List.copyOf(features);
    }
}
