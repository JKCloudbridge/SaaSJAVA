package app.platform.tenant;

import app.platformapi.ApiException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The short name of an organization and the first label of its host name: {@code <slug>.<platform-domain>}
 * (ADR-0017).
 *
 * <p>A slug is 3 to 40 characters: lower-case letters, digits and single hyphens, starting with a letter and ending
 * with a letter or digit. It must also not be one of the {@linkplain #RESERVED reserved names}, which the platform
 * itself uses as host names. The canonical constructor checks only the format, so a slug read from the database
 * can always be rebuilt; {@link #of(String)} also checks the reserved names and is what a new organization uses.
 *
 * @param value the slug text
 */
public record TenantSlug(String value) {

    /** Shortest accepted slug. */
    public static final int MIN_LENGTH = 3;

    /** Longest accepted slug. A DNS label may have 63 characters; 40 keeps host names readable. */
    public static final int MAX_LENGTH = 40;

    /**
     * Names the platform keeps for itself (its own sites, services and likely future ones). A request to one of them
     * is a platform request without a tenant, never an organization.
     */
    public static final Set<String> RESERVED = Set.of(
            "www", "api", "app", "admin", "platform", "support", "mail", "email", "smtp", "static", "assets", "cdn",
            "status", "docs", "help", "login", "logout", "signin", "signup", "auth", "oauth", "sso", "billing",
            "localhost", "internal", "root", "system", "tenant", "tenants", "console", "dashboard", "portal", "web",
            "ftp", "ns1", "ns2", "mx", "test", "staging", "dev", "demo", "metrics", "health", "monitor", "grafana",
            "security", "abuse", "postmaster", "webmaster", "hostmaster", "noreply", "no-reply");

    private static final Pattern FORMAT = Pattern.compile("[a-z][a-z0-9]*(-[a-z0-9]+)*");

    public TenantSlug {
        Objects.requireNonNull(value, "value");
        formatProblem(value).ifPresent(problem -> {
            throw new IllegalArgumentException(problem);
        });
    }

    /**
     * Validates user-supplied text for a new organization.
     *
     * @param text the proposed slug; it is used as given, never trimmed or lower-cased on the caller's behalf
     * @return the slug
     * @throws ApiException a validation error for the field {@code slug} when the text is not acceptable
     */
    public static TenantSlug of(String text) {
        Optional<String> problem = problem(text);
        if (problem.isPresent()) {
            throw ApiException.validation("slug", problem.get());
        }
        return new TenantSlug(text);
    }

    /** What is wrong with a proposed slug for a new organization, if anything; the text is safe to show. */
    public static Optional<String> problem(String text) {
        if (text == null) {
            return Optional.of("Is required.");
        }
        Optional<String> format = formatProblem(text);
        if (format.isPresent()) {
            return format;
        }
        if (isReserved(text)) {
            return Optional.of("Is not available.");
        }
        return Optional.empty();
    }

    /** Whether the label is one of the names the platform keeps for itself. */
    public static boolean isReserved(String label) {
        return RESERVED.contains(label);
    }

    private static Optional<String> formatProblem(String text) {
        if (text.length() < MIN_LENGTH || text.length() > MAX_LENGTH) {
            return Optional.of("Must have " + MIN_LENGTH + " to " + MAX_LENGTH + " characters.");
        }
        if (!FORMAT.matcher(text).matches()) {
            return Optional.of("Must use lower-case letters, digits and single hyphens, start with a letter and "
                    + "end with a letter or digit.");
        }
        return Optional.empty();
    }

    @Override
    public String toString() {
        return value;
    }
}
