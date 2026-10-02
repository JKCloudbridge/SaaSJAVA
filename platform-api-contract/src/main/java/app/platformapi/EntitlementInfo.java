package app.platformapi;

import jakarta.validation.constraints.NotNull;

/**
 * Whether an organization has a feature, and why.
 *
 * @param key the feature key
 * @param name the feature name for people
 * @param inPlan whether the plan includes it
 * @param override the explicit switch for this organization, absent when there is none
 * @param enabled the effective answer
 */
public record EntitlementInfo(@NotNull String key, @NotNull String name, boolean inPlan, Boolean override,
        boolean enabled) {
}
