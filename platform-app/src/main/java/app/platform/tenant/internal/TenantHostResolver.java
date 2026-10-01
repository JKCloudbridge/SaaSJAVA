package app.platform.tenant.internal;

import app.platform.tenant.Tenant;
import app.platform.tenant.Tenants;
import app.platformapi.ApiException;
import app.platformapi.ErrorCode;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Decides which organization a host name addresses and whether it may be served (ADR-0017).
 *
 * <p>The answer rests on the host name only. It is a routing decision, not an authorization: from Sprint 5 the
 * authenticated identity must also hold a membership in the organization the host names.
 */
@Component
class TenantHostResolver {

    /** What a host means for a request. */
    sealed interface Outcome {
    }

    /** The host is not an organization host: the request is a platform request without a tenant. */
    record NoTenant() implements Outcome {
    }

    /** The host names an open organization. */
    record Resolved(Tenant tenant) implements Outcome {
    }

    /** The host names an organization that is unknown or closed; the request is refused with this error. */
    record Refused(ApiException error) implements Outcome {
    }

    private static final Logger LOG = LoggerFactory.getLogger(TenantHostResolver.class);
    private static final Outcome NO_TENANT = new NoTenant();

    private final HostMatcher matcher;
    private final Tenants tenants;

    TenantHostResolver(TenancyProperties properties, Tenants tenants) {
        this.matcher = new HostMatcher(properties.baseDomain());
        this.tenants = tenants;
    }

    /**
     * Resolves a host.
     *
     * @param rawHost the host as the request carries it
     * @return what to do with the request
     */
    Outcome resolve(String rawHost) {
        return switch (matcher.match(rawHost)) {
            case HostMatcher.PlatformHost _ -> NO_TENANT;
            // Custom domains: this is the branch that will look the host up in a table of verified domains.
            case HostMatcher.ForeignHost _ -> NO_TENANT;
            case HostMatcher.InvalidTenantHost _ -> refuse(ErrorCode.NOT_FOUND, "invalid label");
            case HostMatcher.TenantHost host -> {
                Optional<Tenant> tenant = tenants.findBySlug(host.slug());
                if (tenant.isEmpty()) {
                    yield refuse(ErrorCode.NOT_FOUND, "unknown organization");
                }
                if (!tenant.get().status().isOpen()) {
                    // Suspended, deactivated and still-provisioning look the same from outside.
                    yield refuse(ErrorCode.TENANT_UNAVAILABLE, "organization not open");
                }
                yield new Resolved(tenant.get());
            }
        };
    }

    private static Outcome refuse(ErrorCode code, String reason) {
        // The host name is client input: it is not logged, only the reason class.
        LOG.info("Host refused: {}", reason);
        return new Refused(new ApiException(code, code == ErrorCode.NOT_FOUND
                ? "No organization exists at this address." : code.defaultMessage()));
    }
}
