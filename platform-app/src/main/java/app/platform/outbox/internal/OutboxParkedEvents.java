package app.platform.outbox.internal;

import app.platform.sharedkernel.ActorId;
import app.platform.sharedkernel.events.ParkedEvents;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The parked events of the current organization (ADR-0143): the dead letters of the outbox, listed and put back by the
 * module that depends on its handlers seeing them. It runs in the caller's transaction and tenant context, so the row
 * level security of {@code outbox_event} shows and changes only the organization's own events: no system scope is used
 * and no other organization's event can be seen or put back.
 *
 * <p>Putting an event back resets it as if it were new. That is safe because consumers are idempotent (ADR-0016): the
 * marker of a consumer that handled the event already makes it skip, and one that did not handles it.
 */
@Repository
class OutboxParkedEvents implements ParkedEvents {

    private final JdbcClient jdbc;

    OutboxParkedEvents(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Parked> list(Set<String> eventTypes, int limit) {
        if (eventTypes.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("select id, event_type, created_at, attempts, last_error_type from outbox_event "
                        + "where status = 'DEAD' and event_type in (:types) and deleted_at is null "
                        + "order by created_at desc, id desc limit :limit")
                .param("types", eventTypes).param("limit", Math.max(1, limit))
                .query((rs, row) -> new Parked(rs.getObject("id", UUID.class), rs.getString("event_type"),
                        rs.getTimestamp("created_at").toInstant(), rs.getInt("attempts"),
                        rs.getString("last_error_type")))
                .list();
    }

    @Override
    public long count(Set<String> eventTypes) {
        if (eventTypes.isEmpty()) {
            return 0;
        }
        return jdbc.sql("select count(*) from outbox_event where status = 'DEAD' and event_type in (:types) "
                        + "and deleted_at is null")
                .param("types", eventTypes).query(Long.class).single();
    }

    @Override
    public boolean redrive(UUID eventId, Set<String> eventTypes) {
        if (eventTypes.isEmpty()) {
            return false;
        }
        return jdbc.sql("update outbox_event set status = 'PENDING', attempts = 0, next_attempt_at = now(), "
                        + "locked_until = null, last_error_type = null, version = version + 1, updated_by = :system "
                        + "where id = :id and status = 'DEAD' and event_type in (:types) and deleted_at is null")
                .param("system", ActorId.SYSTEM.value()).param("id", eventId).param("types", eventTypes)
                .update() == 1;
    }
}
