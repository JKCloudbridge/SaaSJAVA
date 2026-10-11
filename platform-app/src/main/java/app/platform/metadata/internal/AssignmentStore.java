package app.platform.metadata.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Every statement about layout assignments (ADR-0071): which layout an object uses for a profile, and optionally a
 * record type. Under the tenant context that was open when the transaction began; every change carries the version it
 * was based on.
 */
@Repository
class AssignmentStore {

    /**
     * A stored assignment.
     *
     * @param profileId the profile, or null for the default layout of the object
     * @param recordType the record type, or null
     */
    record Row(UUID id, String objectApiName, UUID profileId, String recordType, String layout, long version) {
    }

    private static final String COLUMNS = "id, object_api_name, profile_id, record_type_api_name, layout_api_name, "
            + "version";

    private final JdbcClient jdbc;

    AssignmentStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Every assignment of the organization, grouped by object. */
    List<Row> all() {
        return jdbc.sql("select " + COLUMNS + " from layout_assignment where deleted_at is null "
                + "order by lower(object_api_name), created_at, id").query(AssignmentStore::row).list();
    }

    Optional<Row> find(String objectApiName, UUID profileId, String recordType) {
        return jdbc.sql("select " + COLUMNS + " from layout_assignment where object_api_name = :object "
                        + "and profile_id is not distinct from cast(:profile as uuid) "
                        + "and record_type_api_name is not distinct from cast(:type as text) "
                        + "and deleted_at is null")
                .param("object", objectApiName).param("profile", profileId).param("type", recordType)
                .query(AssignmentStore::row).optional();
    }

    int count(String objectApiName) {
        return jdbc.sql("select count(*) from layout_assignment where object_api_name = :object "
                + "and deleted_at is null").param("object", objectApiName).query(Integer.class).single();
    }

    Row insert(String objectApiName, UUID profileId, String recordType, String layout, UUID actor) {
        return jdbc.sql("insert into layout_assignment (object_api_name, profile_id, record_type_api_name, "
                        + "layout_api_name, created_by, updated_by) values (:object, cast(:profile as uuid), "
                        + "cast(:type as text), :layout, :actor, :actor) returning " + COLUMNS)
                .param("object", objectApiName).param("profile", profileId).param("type", recordType)
                .param("layout", layout).param("actor", actor).query(AssignmentStore::row).single();
    }

    /** Points the assignment at another layout when it still has the version the caller read. */
    boolean update(UUID id, long version, String layout, UUID actor) {
        return jdbc.sql("update layout_assignment set layout_api_name = :layout, version = version + 1, "
                        + "updated_by = :actor where id = :id and version = :version and deleted_at is null")
                .param("layout", layout).param("actor", actor).param("id", id).param("version", version)
                .update() == 1;
    }

    void delete(UUID id, UUID actor) {
        jdbc.sql("update layout_assignment set deleted_at = now(), deleted_by = :actor, version = version + 1, "
                        + "updated_by = :actor where id = :id and deleted_at is null")
                .param("actor", actor).param("id", id).update();
    }

    private static Row row(ResultSet rs, int row) throws SQLException {
        return new Row(rs.getObject("id", UUID.class), rs.getString("object_api_name"),
                rs.getObject("profile_id", UUID.class), rs.getString("record_type_api_name"),
                rs.getString("layout_api_name"), rs.getLong("version"));
    }
}
