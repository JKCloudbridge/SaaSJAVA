package app.platform.outbox.internal;

import app.platform.sharedkernel.ActorId;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The idempotency keys of consumers: one row per (consumer, event) in {@code processed_event}.
 *
 * <p>The row is written in the same transaction as the handler's own changes, so it exists exactly when those changes
 * were committed. The unique index decides races: when two instances handle one event at the same moment, the second
 * insert waits for the first transaction to finish, and then either finds the marker (the first committed: skip) or
 * does not (the first rolled back: handle).
 */
@Repository
class EventMarkers {

    private final JdbcClient jdbc;

    EventMarkers(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Records that the consumer handles the event now. Runs under the event's tenant context, inside the handler's
     * transaction.
     *
     * @return true when this is the first time (the handler must run), false when the consumer already handled it
     */
    boolean firstTime(String consumer, UUID eventId) {
        return jdbc.sql("insert into processed_event (consumer, event_id, created_by, updated_by) "
                        + "values (:consumer, :event, :system, :system) "
                        + "on conflict (consumer, event_id) where deleted_at is null do nothing")
                .param("consumer", consumer)
                .param("event", eventId)
                .param("system", ActorId.SYSTEM.value())
                .update() == 1;
    }
}
