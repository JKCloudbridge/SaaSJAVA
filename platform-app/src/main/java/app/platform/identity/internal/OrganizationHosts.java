package app.platform.identity.internal;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Builds the host names of organizations on the server, so the browser never composes an organization's address
 * (ADR-0025). An organization lives at {@code <slug>.<platform domain>}; the port is the one the request came in on.
 */
@Component
class OrganizationHosts {

    private final String baseDomain;

    OrganizationHosts(@Value("${platform.tenancy.base-domain}") String baseDomain) {
        this.baseDomain = baseDomain.strip().toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * The host of an organization.
     *
     * @param slug the organization's short name
     * @param authority the host (with the port, when there is one) the current request came to
     */
    String of(String slug, String authority) {
        int colon = authority.lastIndexOf(':');
        boolean hasPort = colon >= 0 && !authority.startsWith("[") && colon < authority.length() - 1
                && authority.substring(colon + 1).chars().allMatch(Character::isDigit);
        return slug + "." + baseDomain + (hasPort ? authority.substring(colon) : "");
    }
}
