package app.platform.tenant;

import app.platform.sharedkernel.TenantId;
import java.time.Instant;
import java.util.Objects;

/**
 * An organization that uses the platform (ADR-0014).
 *
 * @param id the identifier; it is also the {@code tenant_id} of everything the organization owns
 * @param slug the first label of its host name
 * @param displayName its name for people
 * @param status where it is in its life
 * @param statusChangedAt when it entered its current status
 * @param version the optimistic concurrency counter of the row (ADR-0010)
 */
public record Tenant(
        TenantId id,
        TenantSlug slug,
        String displayName,
        TenantStatus status,
        Instant statusChangedAt,
        long version) {

    public Tenant {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(slug, "slug");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(statusChangedAt, "statusChangedAt");
    }
}
