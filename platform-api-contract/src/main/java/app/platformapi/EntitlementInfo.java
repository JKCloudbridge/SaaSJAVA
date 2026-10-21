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
 * @param removalNote what happens to an organization that loses the feature, in the platform's words, or absent when
 *        the feature has no note (a feature that ships a solution does): shown before the feature is switched off
 */
public record EntitlementInfo(@NotNull String key, @NotNull String name, boolean inPlan, Boolean override,
        boolean enabled, String removalNote) {

    /** A feature with no note. */
    public EntitlementInfo(String key, String name, boolean inPlan, Boolean override, boolean enabled) {
        this(key, name, inPlan, override, enabled, null);
    }
}
