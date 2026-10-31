package app.platform.integration.internal;

import app.platform.sharedkernel.ActorId;
import app.platform.tenant.TenantContexts;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The only class that names {@code integration_attempt} (Sprint 29, ADR-0234): what a person can see of each attempt
 * to send. Time, duration, how it ended, a stable reason and a class of status, and nothing else: never a body,
 * an address or an identifier of the other system, so nothing here can leak by construction.
 */
@Repository
class AttemptStore {

    /** How an attempt ended. */
    enum Outcome {
        SUCCESS,
        ACCEPTED,
        RETRY,
        PERMANENT,
        INTERRUPTED
    }

    /** One attempt as stored. */
    record Attempt(UUID id, int generation, int attemptNo, Instant startedAt, int durationMs, String outcome,
            String reason, String statusClass, Integer httpStatus) {
    }

    private static final UUID SYSTEM = ActorId.SYSTEM.value();

    private final JdbcClient jdbc;
    private final TenantContexts contexts;

    AttemptStore(JdbcClient jdbc, TenantContexts contexts) {
        this.jdbc = jdbc;
        this.contexts = contexts;
    }

    /** Writes one attempt. A repeat of the same attempt (same outcome) is ignored, so a retry cannot double it. */
    void record(UUID delivery, UUID connection, int generation, int attemptNo, Instant startedAt, long durationMs,
            Outcome outcome, String reason, String statusClass, Integer httpStatus) {
        jdbc.sql("insert into integration_attempt (delivery_id, connection_id, generation, attempt_no, started_at, "
                        + "duration_ms, outcome, reason, status_class, http_status, created_by, updated_by) values "
                        + "(:delivery, :connection, :generation, :attemptNo, :started, :duration, :outcome, :reason, "
                        + ":class, :status, :system, :system) "
                        + "on conflict (tenant_id, delivery_id, generation, attempt_no, outcome) "
                        + "where deleted_at is null do nothing")
                .param("delivery", delivery).param("connection", connection).param("generation", generation)
                .param("attemptNo", attemptNo).param("started", java.sql.Timestamp.from(startedAt))
                .param("duration", (int) Math.min(Integer.MAX_VALUE, Math.max(0, durationMs)))
                .param("outcome", outcome.name()).param("reason", reason)
                .param("class", statusClass == null ? "none" : statusClass).param("status", httpStatus)
                .param("system", SYSTEM).update();
    }

    /** The attempts of a delivery, oldest first. */
    List<Attempt> of(UUID delivery, int limit) {
        return jdbc.sql("select id, generation, attempt_no, started_at, duration_ms, outcome, reason, status_class, "
                        + "http_status from integration_attempt where tenant_id = :tenant and delivery_id = :delivery "
                        + "and deleted_at is null order by generation, attempt_no, created_at, id limit :limit")
                .param("tenant", tenant()).param("delivery", delivery).param("limit", limit)
                .query((rs, row) -> {
                    int status = rs.getInt("http_status");
                    return new Attempt(rs.getObject("id", UUID.class), rs.getInt("generation"),
                            rs.getInt("attempt_no"), rs.getTimestamp("started_at").toInstant(),
                            rs.getInt("duration_ms"), rs.getString("outcome"), rs.getString("reason"),
                            rs.getString("status_class"), rs.wasNull() ? null : status);
                }).list();
    }

    private UUID tenant() {
        return contexts.require().tenantId().value();
    }
}
