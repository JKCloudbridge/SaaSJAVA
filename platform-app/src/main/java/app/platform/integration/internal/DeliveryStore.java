package app.platform.integration.internal;

import app.platform.tenant.TenantContexts;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The only class that names {@code integration_delivery} (ADR-0220): one row per send and per accepted change, with
 * identifiers, a stable reason, a status and times. It never holds a value or a payload, so nothing here needs to
 * guard a canary; the guard of the table holds the lifecycle (pending, then one end, then final).
 */
@Repository
class DeliveryStore {

    /** A delivery as stored. */
    record Delivery(UUID id, UUID connectionId, UUID mappingVersionId, String direction, String kind, UUID eventId,
            String object, UUID recordId, String state, String reason, Integer httpStatus, Instant createdAt,
            Instant finishedAt) {
    }

    private static final String COLUMNS = "id, connection_id, mapping_version_id, direction, kind, event_id, "
            + "object_api_name, record_id, state, reason, http_status, created_at, finished_at";

    private final JdbcClient jdbc;
    private final TenantContexts contexts;

    DeliveryStore(JdbcClient jdbc, TenantContexts contexts) {
        this.jdbc = jdbc;
        this.contexts = contexts;
    }

    /**
     * Starts a delivery that has not ended. Empty when this event and connection already have one: the work of one
     * event is made once, whichever instance handles it (ADR-0220).
     */
    Optional<UUID> start(UUID connectionId, UUID versionId, String direction, String kind, UUID eventId,
            String object, UUID recordId, UUID actor) {
        return insert(connectionId, versionId, direction, kind, eventId, object, recordId, "PENDING", null, actor);
    }

    /** Records that nothing was done, and why, as a delivery of its own. */
    Optional<UUID> skipped(UUID connectionId, UUID versionId, String direction, String kind, UUID eventId,
            String object, UUID recordId, String reason, UUID actor) {
        return insert(connectionId, versionId, direction, kind, eventId, object, recordId, "SKIPPED", reason, actor);
    }

    private Optional<UUID> insert(UUID connectionId, UUID versionId, String direction, String kind, UUID eventId,
            String object, UUID recordId, String state, String reason, UUID actor) {
        boolean ended = !"PENDING".equals(state);
        return jdbc.sql("insert into integration_delivery (connection_id, mapping_version_id, direction, kind, "
                        + "event_id, object_api_name, record_id, state, reason, finished_at, created_by, updated_by) "
                        + "values (:connection, :version, :direction, :kind, :event, :object, :record, :state, "
                        + ":reason, case when :ended then now() end, :actor, :actor) "
                        + "on conflict (tenant_id, connection_id, direction, event_id) where deleted_at is null "
                        + "do nothing returning id")
                .param("connection", connectionId).param("version", versionId).param("direction", direction)
                .param("kind", kind).param("event", eventId).param("object", object).param("record", recordId)
                .param("state", state).param("reason", reason).param("ended", ended).param("actor", actor)
                .query(UUID.class).optional();
    }

    /** Ends a pending delivery; false when it had ended already. */
    boolean finish(UUID id, String state, String reason, Integer httpStatus, UUID actor) {
        return jdbc.sql("update integration_delivery set state = :state, reason = :reason, http_status = :status, "
                        + "finished_at = now(), version = version + 1, updated_by = :actor "
                        + "where tenant_id = :tenant and id = :id and state = 'PENDING' and deleted_at is null")
                .param("state", state).param("reason", reason).param("status", httpStatus).param("actor", actor)
                .param("tenant", tenant()).param("id", id).update() == 1;
    }

    List<Delivery> recent(UUID connectionId, int limit) {
        return jdbc.sql("select " + COLUMNS + " from integration_delivery where tenant_id = :tenant "
                        + "and connection_id = :connection and deleted_at is null "
                        + "order by created_at desc, id desc limit :limit")
                .param("tenant", tenant()).param("connection", connectionId).param("limit", limit)
                .query((rs, row) -> delivery(rs)).list();
    }

    /** Removes what is older than the retention, a bounded batch at a time, so the table never grows without end. */
    int purge(UUID connectionId, Duration retention, int batch) {
        return jdbc.sql("delete from integration_delivery where id in (select id from integration_delivery "
                        + "where tenant_id = :tenant and connection_id = :connection and state <> 'PENDING' "
                        + "and created_at < now() - make_interval(secs => :secs) limit :batch)")
                .param("tenant", tenant()).param("connection", connectionId)
                .param("secs", (double) retention.toMillis() / 1000.0).param("batch", batch).update();
    }

    private Delivery delivery(java.sql.ResultSet rs) throws java.sql.SQLException {
        java.sql.Timestamp finished = rs.getTimestamp("finished_at");
        int status = rs.getInt("http_status");
        return new Delivery(rs.getObject("id", UUID.class), rs.getObject("connection_id", UUID.class),
                rs.getObject("mapping_version_id", UUID.class), rs.getString("direction"), rs.getString("kind"),
                rs.getObject("event_id", UUID.class), rs.getString("object_api_name"),
                rs.getObject("record_id", UUID.class), rs.getString("state"), rs.getString("reason"),
                rs.wasNull() ? null : status, rs.getTimestamp("created_at").toInstant(),
                finished == null ? null : finished.toInstant());
    }

    private UUID tenant() {
        return contexts.require().tenantId().value();
    }
}
