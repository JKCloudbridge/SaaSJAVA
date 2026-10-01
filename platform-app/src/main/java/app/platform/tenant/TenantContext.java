package app.platform.tenant;

import app.platform.sharedkernel.TenantId;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Who the current work runs for: the tenant, and when a person is behind it, the user and the membership that
 * connects the user to the tenant (ADR-0014).
 *
 * <p>The tenant is always derived on the server: from the host name now, validated against the authenticated
 * identity and membership from Sprint 5. It is never read from a request header, parameter or body. The context
 * travels with the work: it is set for the duration of a request, restored when an asynchronous job runs and when an
 * event is handled, and it is copied into the database session of each transaction so that row level security
 * restricts the work to this tenant (ADR-0015).
 *
 * @param tenantId the tenant
 * @param userId the user, or null when no person is involved (anonymous request, platform job)
 * @param membershipId the membership of the user in the tenant, or null; only present together with a user
 */
public record TenantContext(TenantId tenantId, UUID userId, UUID membershipId) {

    public TenantContext {
        Objects.requireNonNull(tenantId, "tenantId");
        if (membershipId != null && userId == null) {
            throw new IllegalArgumentException("A membership belongs to a user");
        }
    }

    /** A context without a person: an anonymous request or platform work for the tenant. */
    public static TenantContext of(TenantId tenantId) {
        return new TenantContext(tenantId, null, null);
    }

    /** The user, if a person is behind the work. */
    public Optional<UUID> user() {
        return Optional.ofNullable(userId);
    }

    /** The membership, if a person is behind the work. */
    public Optional<UUID> membership() {
        return Optional.ofNullable(membershipId);
    }
}
