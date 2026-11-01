package app.platform.integration.internal;

import app.platform.sharedkernel.ActorId;
import app.platform.tenant.TenantContexts;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The only class that names {@code integration_poll} (Sprint 29A, ADR-0242): when a file transfer connection next looks
 * at its inbox, the lease that keeps two instances from looking at once, and what the last look found. It holds times,
 * counts and stable reasons and nothing else.
 *
 * <p>{@link #claimDue} is the one statement that reads across organizations, and it runs only in the system scope of
 * the outbound worker; everything else runs in the organization's own tenant context.
 */
@Repository
class PollStore {

    /** A claimed poll: where to run it. */
    record Claimed(UUID id, UUID tenantId, UUID connectionId) {
    }

    /**
     * A poll as stored.
     *
     * @param lastOutcome {@code LOOKED}, {@code HELD} (not allowed to look: off, paused) or {@code FAILED}; null before
     *        the first look
     */
    record Poll(UUID id, UUID connectionId, Instant nextPollAt, Instant lastPollAt, String lastOutcome,
            String lastReason, int filesWaiting) {
    }

    private static final UUID SYSTEM = ActorId.SYSTEM.value();
    private static final String COLUMNS = "id, connection_id, next_poll_at, last_poll_at, last_outcome, last_reason, "
            + "files_waiting";

    private static final String CLAIM = """
            with picked as (
                select id from integration_poll
                 where next_poll_at <= now() and (locked_until is null or locked_until < now())
                   and deleted_at is null
                 order by next_poll_at, id
                 limit :limit
                   for update skip locked
            )
            update integration_poll p
               set locked_until = now() + make_interval(secs => cast(:lease as double precision)),
                   version = p.version + 1, updated_by = :system
              from picked
             where p.id = picked.id
            returning p.id, p.tenant_id, p.connection_id
            """;

    private final JdbcClient jdbc;
    private final TenantContexts contexts;

    PollStore(JdbcClient jdbc, TenantContexts contexts) {
        this.jdbc = jdbc;
        this.contexts = contexts;
    }

    /** Makes the connection's poll if it has none; it is due at once. Tenant context. */
    void ensure(UUID connectionId) {
        jdbc.sql("insert into integration_poll (connection_id, created_by, updated_by) values (:connection, :system, "
                        + ":system) on conflict (tenant_id, connection_id) where deleted_at is null do nothing")
                .param("connection", connectionId).param("system", SYSTEM).update();
    }

    /** The polls that are due, locked for the lease. Only in the system scope of the outbound worker. */
    List<Claimed> claimDue(int limit, Duration lease) {
        return jdbc.sql(CLAIM).param("limit", limit).param("lease", lease.toMillis() / 1000.0)
                .param("system", SYSTEM)
                .query((rs, row) -> new Claimed(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                        rs.getObject("connection_id", UUID.class)))
                .list();
    }

    /** The poll of a connection. Tenant context. */
    Optional<Poll> find(UUID connectionId) {
        return jdbc.sql("select " + COLUMNS + " from integration_poll where tenant_id = :tenant "
                        + "and connection_id = :connection and deleted_at is null")
                .param("tenant", tenant()).param("connection", connectionId).query((rs, row) -> poll(rs))
                .optional();
    }

    /** Ends a look: releases the lease, says when to look next and what this look found. Tenant context. */
    void finish(UUID id, Instant next, String outcome, String reason, int filesWaiting) {
        jdbc.sql("update integration_poll set locked_until = null, next_poll_at = :next, last_poll_at = now(), "
                        + "last_outcome = :outcome, last_reason = :reason, files_waiting = :waiting, "
                        + "version = version + 1, updated_by = :system where tenant_id = :tenant and id = :id")
                .param("next", Timestamp.from(next)).param("outcome", outcome).param("reason", reason)
                .param("waiting", filesWaiting).param("system", SYSTEM).param("tenant", tenant()).param("id", id)
                .update();
    }

    /** Takes the poll of a connection that no longer exists out of the claim. Tenant context. */
    void remove(UUID id) {
        jdbc.sql("update integration_poll set deleted_at = now(), deleted_by = :system, version = version + 1, "
                        + "updated_by = :system where tenant_id = :tenant and id = :id and deleted_at is null")
                .param("system", SYSTEM).param("tenant", tenant()).param("id", id).update();
    }

    private static Poll poll(ResultSet rs) throws SQLException {
        Timestamp last = rs.getTimestamp("last_poll_at");
        return new Poll(rs.getObject("id", UUID.class), rs.getObject("connection_id", UUID.class),
                rs.getTimestamp("next_poll_at").toInstant(), last == null ? null : last.toInstant(),
                rs.getString("last_outcome"), rs.getString("last_reason"), rs.getInt("files_waiting"));
    }

    private UUID tenant() {
        return contexts.require().tenantId().value();
    }
}
