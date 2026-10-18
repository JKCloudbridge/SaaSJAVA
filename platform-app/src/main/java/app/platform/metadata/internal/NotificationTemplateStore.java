package app.platform.metadata.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Every statement about the text of notices (ADR-0155). Like the rest of the module it runs under the tenant context
 * that was open when the transaction began; a change carries the version it was based on.
 */
@Repository
class NotificationTemplateStore {

    /**
     * A stored text.
     *
     * @param id the row
     * @param kind the kind of notice
     * @param title the title
     * @param body the body
     * @param version the version of the row
     */
    record Row(UUID id, String kind, String title, String body, long version) {
    }

    private final JdbcClient jdbc;

    NotificationTemplateStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    List<Row> all() {
        return jdbc.sql("select id, kind, title, body, version from notification_template "
                        + "where deleted_at is null order by kind")
                .query(NotificationTemplateStore::row).list();
    }

    Optional<Row> find(String kind) {
        return jdbc.sql("select id, kind, title, body, version from notification_template "
                        + "where kind = :kind and deleted_at is null")
                .param("kind", kind).query(NotificationTemplateStore::row).optional();
    }

    Row insert(String kind, String title, String body, UUID actor) {
        jdbc.sql("insert into notification_template (kind, title, body, created_by, updated_by) "
                        + "values (:kind, :title, :body, :actor, :actor)")
                .param("kind", kind).param("title", title).param("body", body).param("actor", actor).update();
        return find(kind).orElseThrow();
    }

    /** Changes the text when it still has the version the caller read; false when it does not. */
    boolean update(UUID id, long version, String title, String body, UUID actor) {
        return jdbc.sql("update notification_template set title = :title, body = :body, version = version + 1, "
                        + "updated_by = :actor where id = :id and version = :version and deleted_at is null")
                .param("title", title).param("body", body).param("actor", actor).param("id", id)
                .param("version", version).update() == 1;
    }

    void delete(UUID id, UUID actor) {
        jdbc.sql("update notification_template set deleted_at = now(), deleted_by = :actor, version = version + 1, "
                        + "updated_by = :actor where id = :id and deleted_at is null")
                .param("actor", actor).param("id", id).update();
    }

    private static Row row(ResultSet rs, int row) throws SQLException {
        return new Row(rs.getObject("id", UUID.class), rs.getString("kind"), rs.getString("title"),
                rs.getString("body"), rs.getLong("version"));
    }
}
