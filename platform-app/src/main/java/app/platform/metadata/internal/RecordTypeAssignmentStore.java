package app.platform.metadata.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Every statement about record type assignments (Sprint 15A, ADR-0098): which profiles may use which record type of an
 * object, and which type is the default of a profile. Under the tenant context that was open when the transaction
 * began; every change carries the version it was based on.
 */
@Repository
class RecordTypeAssignmentStore {

    /** A stored assignment. */
    record Row(UUID id, String objectApiName, String recordType, UUID profileId, boolean defaultForProfile,
            long version) {
    }

    private static final String COLUMNS = "id, object_api_name, record_type_api_name, profile_id, is_default, version";

    private final JdbcClient jdbc;

    RecordTypeAssignmentStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Every assignment of the organization, in the order they were made. */
    List<Row> all() {
        return jdbc.sql("select " + COLUMNS + " from record_type_assignment where deleted_at is null "
                + "order by lower(object_api_name), created_at, id").query(RecordTypeAssignmentStore::row).list();
    }

    Optional<Row> find(String objectApiName, String recordType, UUID profileId) {
        return jdbc.sql("select " + COLUMNS + " from record_type_assignment where object_api_name = :object "
                        + "and record_type_api_name = :type and profile_id = :profile and deleted_at is null")
                .param("object", objectApiName).param("type", recordType).param("profile", profileId)
                .query(RecordTypeAssignmentStore::row).optional();
    }

    /** The assignment of the profile that is marked as its default for the object, if there is one. */
    Optional<Row> defaultOf(String objectApiName, UUID profileId) {
        return jdbc.sql("select " + COLUMNS + " from record_type_assignment where object_api_name = :object "
                        + "and profile_id = :profile and is_default and deleted_at is null")
                .param("object", objectApiName).param("profile", profileId)
                .query(RecordTypeAssignmentStore::row).optional();
    }

    int count(String objectApiName) {
        return jdbc.sql("select count(*) from record_type_assignment where object_api_name = :object "
                + "and deleted_at is null").param("object", objectApiName).query(Integer.class).single();
    }

    Row insert(String objectApiName, String recordType, UUID profileId, boolean defaultForProfile, UUID actor) {
        return jdbc.sql("insert into record_type_assignment (object_api_name, record_type_api_name, profile_id, "
                        + "is_default, created_by, updated_by) values (:object, :type, :profile, :default, :actor, "
                        + ":actor) returning " + COLUMNS)
                .param("object", objectApiName).param("type", recordType).param("profile", profileId)
                .param("default", defaultForProfile).param("actor", actor)
                .query(RecordTypeAssignmentStore::row).single();
    }

    /** Sets or clears the default mark when the assignment still has the version the caller read. */
    boolean updateDefault(UUID id, long version, boolean defaultForProfile, UUID actor) {
        return jdbc.sql("update record_type_assignment set is_default = :default, version = version + 1, "
                        + "updated_by = :actor where id = :id and version = :version and deleted_at is null")
                .param("default", defaultForProfile).param("actor", actor).param("id", id)
                .param("version", version).update() == 1;
    }

    /** Takes the default mark off the assignment (used when another becomes the profile's default). */
    void clearDefault(UUID id, UUID actor) {
        jdbc.sql("update record_type_assignment set is_default = false, version = version + 1, "
                        + "updated_by = :actor where id = :id and is_default and deleted_at is null")
                .param("actor", actor).param("id", id).update();
    }

    void delete(UUID id, UUID actor) {
        jdbc.sql("update record_type_assignment set deleted_at = now(), deleted_by = :actor, is_default = false, "
                        + "version = version + 1, updated_by = :actor where id = :id and deleted_at is null")
                .param("actor", actor).param("id", id).update();
    }

    private static Row row(ResultSet rs, int row) throws SQLException {
        return new Row(rs.getObject("id", UUID.class), rs.getString("object_api_name"),
                rs.getString("record_type_api_name"), rs.getObject("profile_id", UUID.class),
                rs.getBoolean("is_default"), rs.getLong("version"));
    }
}
