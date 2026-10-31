package app.platform.integration.internal;

import app.platform.integration.internal.CircuitBreaker.Forced;
import app.platform.integration.internal.CircuitBreaker.Mode;
import app.platform.integration.internal.CircuitBreaker.State;
import app.platform.integration.internal.RateLimiter.Bucket;
import app.platform.sharedkernel.ActorId;
import app.platform.tenant.TenantContexts;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The only class that names {@code integration_flow_state} (Sprint 29, ADR-0229, ADR-0232): the circuit breaker, the
 * token bucket and the operator's limits of one connection. One row, locked while it is decided, so every instance
 * agrees and two cannot both take the last token or both send the probe. It holds counts, times and stable reasons
 * only.
 */
@Repository
class FlowStateStore {

    /**
     * A flow state as stored.
     *
     * @param circuit the breaker
     * @param bucket the token bucket
     * @param ratePerMinute the operator's rate, or null for the platform's default
     * @param maxConcurrent the operator's concurrency, or null for the platform's default
     */
    record Flow(UUID id, UUID connectionId, State circuit, Bucket bucket, Integer ratePerMinute,
            Integer maxConcurrent, Instant lastSuccessAt, Instant lastFailureAt, String lastFailureReason,
            Instant openedAt, Instant forcedAt, long version) {
    }

    private static final String COLUMNS = "id, connection_id, circuit_state, consecutive_failures, opened_at, "
            + "open_until, probes_in_flight, probe_started_at, forced, forced_at, last_success_at, last_failure_at, "
            + "last_failure_reason, tokens, tokens_at, rate_per_minute, max_concurrent, version";
    private static final UUID SYSTEM = ActorId.SYSTEM.value();

    private final JdbcClient jdbc;
    private final TenantContexts contexts;

    FlowStateStore(JdbcClient jdbc, TenantContexts contexts) {
        this.jdbc = jdbc;
        this.contexts = contexts;
    }

    /** The row of the connection, made if it does not exist yet, and locked until the transaction ends. */
    Flow lock(UUID connectionId, double initialTokens) {
        jdbc.sql("insert into integration_flow_state (connection_id, tokens, created_by, updated_by) "
                        + "values (:connection, :tokens, :system, :system) "
                        + "on conflict (tenant_id, connection_id) where deleted_at is null do nothing")
                .param("connection", connectionId).param("tokens", initialTokens).param("system", SYSTEM).update();
        return jdbc.sql("select " + COLUMNS + " from integration_flow_state where tenant_id = :tenant "
                        + "and connection_id = :connection and deleted_at is null for update")
                .param("tenant", tenant()).param("connection", connectionId).query((rs, row) -> flow(rs)).single();
    }

    /** The row without a lock, or empty when the connection has never been sent to. */
    Optional<Flow> find(UUID connectionId) {
        return jdbc.sql("select " + COLUMNS + " from integration_flow_state where tenant_id = :tenant "
                        + "and connection_id = :connection and deleted_at is null")
                .param("tenant", tenant()).param("connection", connectionId).query((rs, row) -> flow(rs)).optional();
    }

    /** Stores the breaker and the bucket, and what was learned. The row must be the one {@link #lock} returned. */
    void save(UUID id, State circuit, Bucket bucket, Instant openedAt, Instant lastSuccessAt, Instant lastFailureAt,
            String lastFailureReason) {
        jdbc.sql("update integration_flow_state set circuit_state = :mode, consecutive_failures = :failures, "
                        + "opened_at = :openedAt, open_until = :openUntil, probes_in_flight = :probes, "
                        + "probe_started_at = :probeAt, tokens = :tokens, tokens_at = :tokensAt, "
                        + "last_success_at = :success, last_failure_at = :failure, last_failure_reason = :reason, "
                        + "version = version + 1, updated_by = :system where tenant_id = :tenant and id = :id")
                .param("mode", circuit.mode().name()).param("failures", circuit.consecutiveFailures())
                .param("openedAt", ts(openedAt)).param("openUntil", ts(circuit.openUntil()))
                .param("probes", circuit.probesInFlight()).param("probeAt", ts(circuit.probeStartedAt()))
                .param("tokens", bucket.tokens()).param("tokensAt", ts(bucket.at()))
                .param("success", ts(lastSuccessAt)).param("failure", ts(lastFailureAt))
                .param("reason", lastFailureReason).param("system", SYSTEM).param("tenant", tenant())
                .param("id", id).update();
    }

    /** Records who forced the circuit and when, or that nobody does. */
    void markForced(UUID id, Forced forced, UUID by) {
        jdbc.sql("update integration_flow_state set forced = cast(:forced as text), forced_by = cast(:by as uuid), "
                        + "forced_at = case when cast(:forced as text) is null then null else now() end, "
                        + "version = version + 1, updated_by = :system where tenant_id = :tenant and id = :id")
                .param("forced", forced == null ? null : forced.name()).param("by", forced == null ? null : by)
                .param("system", SYSTEM).param("tenant", tenant()).param("id", id).update();
    }

    /** Sets the operator's limits; a null leaves the platform's default in force. */
    void setLimits(UUID id, Integer ratePerMinute, Integer maxConcurrent) {
        jdbc.sql("update integration_flow_state set rate_per_minute = :rate, max_concurrent = :concurrent, "
                        + "version = version + 1, updated_by = :system where tenant_id = :tenant and id = :id")
                .param("rate", ratePerMinute).param("concurrent", maxConcurrent).param("system", SYSTEM)
                .param("tenant", tenant()).param("id", id).update();
    }

    private Flow flow(ResultSet rs) throws SQLException {
        Instant openUntil = instant(rs, "open_until");
        String forced = rs.getString("forced");
        State state = new State(Mode.valueOf(rs.getString("circuit_state")), rs.getInt("consecutive_failures"),
                openUntil, rs.getInt("probes_in_flight"), instant(rs, "probe_started_at"),
                forced == null ? null : Forced.valueOf(forced));
        int rate = rs.getInt("rate_per_minute");
        Integer ratePerMinute = rs.wasNull() ? null : rate;
        int concurrent = rs.getInt("max_concurrent");
        Integer maxConcurrent = rs.wasNull() ? null : concurrent;
        return new Flow(rs.getObject("id", UUID.class), rs.getObject("connection_id", UUID.class), state,
                new Bucket(rs.getDouble("tokens"), rs.getTimestamp("tokens_at").toInstant()), ratePerMinute,
                maxConcurrent, instant(rs, "last_success_at"), instant(rs, "last_failure_at"),
                rs.getString("last_failure_reason"), instant(rs, "opened_at"), forcedAt(rs), rs.getLong("version"));
    }

    private static Instant forcedAt(ResultSet rs) throws SQLException {
        return instant(rs, "forced_at");
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private UUID tenant() {
        return contexts.require().tenantId().value();
    }
}
