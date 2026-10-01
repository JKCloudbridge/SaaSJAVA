package app.platform.sharedkernel;

import java.util.Objects;
import java.util.UUID;

/**
 * Opaque identifier of a tenant. The tenant is never taken from client input; it is derived from
 * the authenticated identity, membership and host (see ADR-0003).
 *
 * @param value the underlying identifier
 */
public record TenantId(UUID value) {

    public TenantId {
        Objects.requireNonNull(value, "value");
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
