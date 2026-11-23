package app.platform.integration.internal;

import app.platform.sharedkernel.ActorId;
import app.platform.tenant.TenantContexts;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The only class that names {@code webhook_attempt} (Sprint 32, ADR-0278): one immutable row for each attempt to send a
 * delivery that was made or lost, with when, how long, how it ended, a stable reason and the class of the answer.
 * Never a
 * payload, a header, an address or any part of an answer: the database checks the shape of a reason, and nothing here
 * accepts a free text. Runs in the organization's own tenant context.
 */
@Repository
class WebhookAttemptStore {

    /** How an attempt ended. */
    enum Outcome {
        SUCCESS,
        RETRY,
        PERMANENT,
        INTERRUPTED
    }

    /** One attempt as stored. */
    record Attempt(int generation, int attemptNo, Instant startedAt, int durationMs, String outcome, String reason,
            String statusClass, Integer httpStatus) {
    }

    private final JdbcClient jdbc;
    private final TenantContexts contexts;

    WebhookAttemptStore(JdbcClient jdbc, TenantContexts contexts) {
        this.jdbc = jdbc;
        this.contexts = contexts;
    }

    /** Writes the record of an attempt. A repeat of the same attempt and outcome is ignored. */
    void record(UUID deliveryId, UUID subscriptionId, int generation, int attemptNo, Instant startedAt,
            long durationMs, Outcome outcome, String reason, String statusClass, Integer httpStatus) {
        jdbc.sql("insert into webhook_attempt (delivery_id, subscription_id, generation, attempt_no, started_at, "
                        + "duration_ms, outcome, reason, status_class, http_status, created_by, updated_by) "
                        + "values (:delivery, :subscription, :generation, :attemptNo, :startedAt, :duration, "
                        + ":outcome, :reason, :class, :status, :system, :system) "
                        + "on conflict (tenant_id, delivery_id, generation, attempt_no, outcome) "
                        + "where deleted_at is null do nothing")
                .param("delivery", deliveryId).param("subscription", subscriptionId)
                .param("generation", generation).param("attemptNo", Math.max(1, attemptNo))
                .param("startedAt", Timestamp.from(startedAt))
                .param("duration", (int) Math.min(Integer.MAX_VALUE, Math.max(0, durationMs)))
                .param("outcome", outcome.name()).param("reason", reason, Types.VARCHAR)
                .param("class", statusClass == null ? "none" : statusClass)
                .param("status", httpStatus, Types.INTEGER).param("system", ActorId.SYSTEM.value()).update();
    }

    /** The attempts of a delivery, oldest first, up to {@code limit}. */
    List<Attempt> of(UUID deliveryId, int limit) {
        return jdbc.sql("select generation, attempt_no, started_at, duration_ms, outcome, reason, status_class, "
                        + "http_status from webhook_attempt where tenant_id = :tenant and delivery_id = :delivery "
                        + "and deleted_at is null order by generation, attempt_no, created_at limit :limit")
                .param("tenant", contexts.require().tenantId().value()).param("delivery", deliveryId)
                .param("limit", limit)
                .query((rs, row) -> {
                    int status = rs.getInt("http_status");
                    return new Attempt(rs.getInt("generation"), rs.getInt("attempt_no"),
                            rs.getTimestamp("started_at").toInstant(), rs.getInt("duration_ms"),
                            rs.getString("outcome"), rs.getString("reason"), rs.getString("status_class"),
                            rs.wasNull() ? null : status);
                }).list();
    }
}
