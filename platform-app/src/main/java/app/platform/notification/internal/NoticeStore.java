package app.platform.notification.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every statement about the notice tables (ADR-0154). Like the rest of the module it runs under the tenant context that
 * was open when the transaction began, and row level security limits it to that organization. Nobody else names
 * {@code notification} (an architecture test).
 */
@Repository
class NoticeStore {

    /**
     * A stored notice.
     *
     * @param id the notice
     * @param kind the kind
     * @param objectApiName the object of the record it is about, or null
     * @param recordId the record it is about, or null
     * @param facts the facts of the kind
     * @param readAt when it was read, or null
     * @param createdAt when it was made
     */
    record Row(UUID id, String kind, String objectApiName, UUID recordId, Map<String, String> facts, Instant readAt,
            Instant createdAt) {
    }

    private static final TypeReference<Map<String, String>> FACTS = new TypeReference<>() { };
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String COLUMNS = "id, kind, object_api_name, record_id, facts::text as facts, read_at, "
            + "created_at";

    private final JdbcClient jdbc;

    NoticeStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Makes the notice once per recipient and key. @return true when it was made, false when it existed */
    boolean insert(UUID recipient, String kind, String object, UUID record, Map<String, String> facts, String key,
            UUID actor) {
        return jdbc.sql("insert into notification (recipient_id, kind, object_api_name, record_id, facts, dedupe_key, "
                        + "created_by, updated_by) values (:recipient, :kind, :object, :record, cast(:facts as jsonb), "
                        + ":key, :actor, :actor) on conflict (tenant_id, recipient_id, dedupe_key) "
                        + "where deleted_at is null do nothing")
                .param("recipient", recipient).param("kind", kind).param("object", object).param("record", record)
                .param("facts", JSON.writeValueAsString(facts)).param("key", key).param("actor", actor)
                .update() == 1;
    }

    /** A page of one person's notices, newest first, after the position the cursor holds. */
    List<Row> page(UUID recipient, boolean unreadOnly, Instant before, UUID beforeId, int limit) {
        if (before == null) {
            return jdbc.sql("select " + COLUMNS + " from notification where recipient_id = :recipient "
                            + "and deleted_at is null and (:unreadOnly = false or read_at is null) "
                            + "order by created_at desc, id desc limit :limit")
                    .param("recipient", recipient).param("unreadOnly", unreadOnly).param("limit", limit)
                    .query(NoticeStore::row).list();
        }
        return jdbc.sql("select " + COLUMNS + " from notification where recipient_id = :recipient "
                        + "and deleted_at is null and (:unreadOnly = false or read_at is null) "
                        + "and (created_at, id) < (:before, :beforeId) "
                        + "order by created_at desc, id desc limit :limit")
                .param("recipient", recipient).param("unreadOnly", unreadOnly)
                .param("before", Timestamp.from(before)).param("beforeId", beforeId).param("limit", limit)
                .query(NoticeStore::row).list();
    }

    int unread(UUID recipient) {
        return jdbc.sql("select count(*) from notification where recipient_id = :recipient and read_at is null "
                        + "and deleted_at is null")
                .param("recipient", recipient).query(Integer.class).single();
    }

    /** Marks one of the person's own notices read. @return true when it was theirs and unread */
    boolean markRead(UUID recipient, UUID id, UUID actor) {
        return jdbc.sql("update notification set read_at = now(), version = version + 1, updated_by = :actor "
                        + "where id = :id and recipient_id = :recipient and read_at is null and deleted_at is null")
                .param("actor", actor).param("id", id).param("recipient", recipient).update() == 1;
    }

    /** Whether the person has a notice with this identifier (read or not). */
    boolean owns(UUID recipient, UUID id) {
        return jdbc.sql("select exists (select 1 from notification where id = :id and recipient_id = :recipient "
                        + "and deleted_at is null)")
                .param("id", id).param("recipient", recipient).query(Boolean.class).single();
    }

    int markAllRead(UUID recipient, UUID actor) {
        return jdbc.sql("update notification set read_at = now(), version = version + 1, updated_by = :actor "
                        + "where recipient_id = :recipient and read_at is null and deleted_at is null")
                .param("actor", actor).param("recipient", recipient).update();
    }

    /**
     * Removes notices past their retention, at most one batch (ADR-0154). Read ones go after {@code readCutoff},
     * unread ones after {@code unreadCutoff}; the caller has already raised both to the floor.
     */
    int purge(Duration readCutoff, Duration unreadCutoff, int batch) {
        return jdbc.sql("delete from notification where id in (select id from notification where "
                        + "(read_at is not null and created_at < now() - make_interval(secs => "
                        + "cast(:read as double precision))) or (read_at is null and created_at < now() - "
                        + "make_interval(secs => cast(:unread as double precision))) limit :batch)")
                .param("read", readCutoff.toMillis() / 1000.0).param("unread", unreadCutoff.toMillis() / 1000.0)
                .param("batch", batch).update();
    }

    private static Row row(ResultSet rs, int row) throws SQLException {
        Timestamp read = rs.getTimestamp("read_at");
        return new Row(rs.getObject("id", UUID.class), rs.getString("kind"), rs.getString("object_api_name"),
                rs.getObject("record_id", UUID.class), JSON.readValue(rs.getString("facts"), FACTS),
                read == null ? null : read.toInstant(), rs.getTimestamp("created_at").toInstant());
    }
}
