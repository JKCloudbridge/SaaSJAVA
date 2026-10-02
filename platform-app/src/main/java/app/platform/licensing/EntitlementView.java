package app.platform.licensing;

/**
 * Whether an organization has a feature, and why (ADR-0034).
 *
 * @param key the feature key
 * @param name the feature's name for people
 * @param inPlan whether the organization's plan includes it
 * @param override the platform administrator's explicit switch for this organization, or null when there is none
 * @param enabled the effective answer: the override when there is one, otherwise the plan
 */
public record EntitlementView(String key, String name, boolean inPlan, Boolean override, boolean enabled) {
}
