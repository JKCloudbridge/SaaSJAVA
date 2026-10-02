package app.platform.licensing;

/**
 * A kind of licence an organization can hold a pool of (ADR-0031). Data, not a fixed list in the code: the first two
 * are
 * {@code user} and {@code admin}.
 *
 * @param key the stable lower-case key
 * @param name the name for people
 */
public record LicenceTypeView(String key, String name) {
}
