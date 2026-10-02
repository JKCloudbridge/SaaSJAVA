package app.platform.licensing;

/**
 * A feature key that a plan may include (ADR-0034).
 *
 * @param key the stable lower-case key, for example {@code approvals}
 * @param name the name for people
 */
public record FeatureView(String key, String name) {
}
