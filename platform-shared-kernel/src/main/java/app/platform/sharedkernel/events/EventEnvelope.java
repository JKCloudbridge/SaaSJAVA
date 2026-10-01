package app.platform.sharedkernel.events;

import app.platform.sharedkernel.TenantId;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * An event as a handler receives it: what happened and the tenant context it happened in. While the handler runs the
 * tenant context of this envelope is active, so database access is restricted to the event's tenant.
 *
 * @param eventId unique identifier of the event; with the handler's consumer name it is the idempotency key
 * @param tenantId the tenant the event belongs to
 * @param userId the user who caused it, or null when it was caused by the platform itself
 * @param membershipId the membership it was caused under, or null
 * @param type what happened, for example {@code tenant.suspended}
 * @param payload the JSON document the publisher supplied
 * @param occurredAt when the event was recorded
 * @param attempt which delivery attempt this is, starting at 1; above 1 means an earlier attempt failed or stalled
 */
public record EventEnvelope(
        UUID eventId,
        TenantId tenantId,
        UUID userId,
        UUID membershipId,
        String type,
        String payload,
        Instant occurredAt,
        int attempt) {

    public EventEnvelope {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(occurredAt, "occurredAt");
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt starts at 1");
        }
    }

    /** The user who caused the event, if a person did. */
    public Optional<UUID> user() {
        return Optional.ofNullable(userId);
    }

    /** The membership the event was caused under, if any. */
    public Optional<UUID> membership() {
        return Optional.ofNullable(membershipId);
    }
}
