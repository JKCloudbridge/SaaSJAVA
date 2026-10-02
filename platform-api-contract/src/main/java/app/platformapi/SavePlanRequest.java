package app.platformapi;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;

/**
 * Creates a plan, or replaces the plan with the same key in the path.
 *
 * @param name the plan name for people
 * @param trialDays how many days a subscription on it starts as a trial, absent when it does not
 * @param licences the default quantity per licence type key
 * @param features the keys of the features it includes
 */
public record SavePlanRequest(@NotBlank @Size(max = 80) String name, Integer trialDays,
        @NotNull Map<String, Integer> licences, @NotNull List<String> features) {

    public SavePlanRequest {
        // A missing list or map stays missing so that validation can say so; a present one is copied.
        licences = licences == null ? null : Map.copyOf(licences);
        features = features == null ? null : List.copyOf(features);
    }
}
