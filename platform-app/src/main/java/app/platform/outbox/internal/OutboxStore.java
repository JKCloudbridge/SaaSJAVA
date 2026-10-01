package app.platform.outbox.internal;

import app.platform.sharedkernel.ActorId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The relay's view of the outbox table. Every method must run inside a transaction in the {@code OUTBOX_RELAY} system
 * scope: that is the only scope the table's policies admit across tenants (ADR-0015).
 *
 * <p><strong>Claiming</strong> is one statement that picks due events with {@code FOR UPDATE SKIP LOCKED}, so several
 * instances polling at once never wait for each other and never pick the same row, and gives each picked event a lease
 * (it is invisible to other pollers until the lease ends, so a crashed instance's events come back by themselves). The
 * claim also counts the attempt, so an event whose handling keeps killing the process is eventually dead-lettered
 * instead of crashing instances forever. The row's version after the claim is the claim's token: completing the event
 * succeeds only while the version is unchanged, so an instance that was too slow and lost its lease cannot overwrite
 * the outcome of the instance that took over.
 */
@Repository
class OutboxStore {

    /** An event this instance has claimed. */
    record ClaimedEvent(UUID id, UUID tenantId, UUID userId, UUID membershipId, String type, String payload,
            java.time.Instant occurredAt, int attempt, long version) {
    }

    private static final int PURGE_BATCH = 1000;

    private final JdbcClient jdbc;

    OutboxStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Claims up to {@code limit} due events, oldest first. */
    List<ClaimedEvent> claim(int limit, Duration lease) {
        List<ClaimedEvent> claimed = jdbc.sql("""
                update outbox_event e
                   set locked_until = now() + make_interval(secs => cast(:lease as double precision)),
                       attempts = e.attempts + 1, version = e.version + 1, updated_by = :system
                 where e.id in (select d.id from outbox_event d
                                 where d.status = 'PENDING' and d.next_attempt_at <= now()
                                   and (d.locked_until is null or d.locked_until < now())
                                 order by d.next_attempt_at, d.id
                                 limit :limit
                                 for update skip locked)
                returning e.id, e.tenant_id, e.user_id, e.membership_id, e.event_type, e.payload::text as payload,
                          e.created_at, e.attempts, e.version
                """)
                .param("lease", lease.toMillis() / 1000.0)
                .param("system", ActorId.SYSTEM.value())
                .param("limit", limit)
                .query(OutboxStore::map)
                .list();
        // The statement does not promise an order; handle in recording order.
        return claimed.stream()
                .sorted(Comparator.comparing(ClaimedEvent::occurredAt).thenComparing(ClaimedEvent::id))
                .toList();
    }

    /** @return false when the claim was lost (the event is no longer at the claimed version) */
    boolean markDelivered(ClaimedEvent event) {
        return finish(event, "status = 'DELIVERED', delivered_at = now(), last_error_type = null", Map.of());
    }

    /** Puts the event back, due again after {@code delay}. @return false when the claim was lost */
    boolean scheduleRetry(ClaimedEvent event, Duration delay, String errorType) {
        return finish(event, "next_attempt_at = now() + make_interval(secs => cast(:delay as double precision)), "
                + "last_error_type = :error", Map.of("delay", delay.toMillis() / 1000.0, "error", errorType));
    }

    /** Sets the event aside as a dead letter. @return false when the claim was lost */
    boolean markDead(ClaimedEvent event, String errorType) {
        return finish(event, "status = 'DEAD', last_error_type = :error", Map.of("error", errorType));
    }

    /** Removes delivered events older than the retention, at most one batch. @return how many were removed */
    int purgeDelivered(Duration retention) {
        return jdbc.sql("delete from outbox_event where id in (select id from outbox_event "
                        + "where status = 'DELIVERED' and delivered_at < now() - make_interval(secs => "
                        + "cast(:seconds as double precision)) limit " + PURGE_BATCH + ")")
                .param("seconds", retention.toMillis() / 1000.0)
                .update();
    }

    /** Removes idempotency markers older than the retention, at most one batch. @return how many were removed */
    int purgeMarkers(Duration retention) {
        return jdbc.sql("delete from processed_event where id in (select id from processed_event "
                        + "where created_at < now() - make_interval(secs => cast(:seconds as double precision)) "
                        + "limit " + PURGE_BATCH + ")")
                .param("seconds", retention.toMillis() / 1000.0)
                .update();
    }

    private boolean finish(ClaimedEvent event, String assignments, Map<String, Object> arguments) {
        var statement = jdbc.sql("update outbox_event set " + assignments + ", locked_until = null, "
                        + "updated_by = :system, version = version + 1 where id = :id and version = :version")
                .param("system", ActorId.SYSTEM.value())
                .param("id", event.id())
                .param("version", event.version());
        for (var argument : arguments.entrySet()) {
            statement = statement.param(argument.getKey(), argument.getValue());
        }
        return statement.update() == 1;
    }

    private static ClaimedEvent map(ResultSet rs, int row) throws SQLException {
        return new ClaimedEvent(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getObject("membership_id", UUID.class),
                rs.getString("event_type"),
                rs.getString("payload"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getInt("attempts"),
                rs.getLong("version"));
    }
}
