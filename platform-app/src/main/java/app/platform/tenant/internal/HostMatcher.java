package app.platform.tenant.internal;

import app.platform.tenant.TenantSlug;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Reads a host name against the platform domain (ADR-0017). Pure string work: no database, no framework.
 *
 * <p>{@code acme.example.test} with the base domain {@code example.test} is the organization host of slug
 * {@code acme}. The base domain itself and the reserved names under it are the platform's own hosts. Anything else
 * under the base domain (two labels, an invalid label) can never be an organization. A host outside the base domain
 * is foreign: no organization is resolved from it today, and it is the place where custom domains plug in later.
 */
final class HostMatcher {

    /** The kinds of host there are. */
    sealed interface Match {
    }

    /** The platform's own host (the base domain or a reserved name under it): a request without a tenant. */
    record PlatformHost() implements Match {
    }

    /** An organization host. The slug has the right form; whether the organization exists is another question. */
    record TenantHost(TenantSlug slug) implements Match {
    }

    /** Under the base domain but not a possible organization host. */
    record InvalidTenantHost() implements Match {
    }

    /** Not under the base domain at all (direct access by address, probes, an unconfigured custom domain). */
    record ForeignHost() implements Match {
    }

    private static final Pattern PLAIN_HOST = Pattern.compile("[a-z0-9.-]+");
    private static final Match PLATFORM = new PlatformHost();
    private static final Match INVALID = new InvalidTenantHost();
    private static final Match FOREIGN = new ForeignHost();

    private final String baseDomain;
    private final String suffix;

    HostMatcher(String baseDomain) {
        this.baseDomain = baseDomain;
        this.suffix = "." + baseDomain;
    }

    /**
     * Classifies a host as it arrives (a {@code Host} or {@code X-Forwarded-Host} value, possibly with a port, in any
     * case, possibly with a trailing dot).
     */
    Match match(String rawHost) {
        Optional<String> host = normalize(rawHost);
        if (host.isEmpty()) {
            return FOREIGN;
        }
        String name = host.get();
        if (name.equals(baseDomain)) {
            return PLATFORM;
        }
        if (!name.endsWith(suffix)) {
            return FOREIGN;
        }
        String label = name.substring(0, name.length() - suffix.length());
        if (TenantSlug.isReserved(label)) {
            return PLATFORM;
        }
        return TenantSlug.problem(label).isPresent() ? INVALID : new TenantHost(new TenantSlug(label));
    }

    /**
     * Lower-cases the host, drops a port and a trailing dot, and rejects anything that is not a plain DNS name
     * (address literals, spaces, control characters, empty labels).
     */
    static Optional<String> normalize(String rawHost) {
        if (rawHost == null) {
            return Optional.empty();
        }
        String host = rawHost.strip().toLowerCase(Locale.ROOT);
        int colon = host.lastIndexOf(':');
        if (colon >= 0) {
            host = host.substring(0, colon);
        }
        if (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        if (host.isEmpty() || host.length() > 253 || !PLAIN_HOST.matcher(host).matches()
                || host.startsWith(".") || host.contains("..")) {
            return Optional.empty();
        }
        return Optional.of(host);
    }
}
