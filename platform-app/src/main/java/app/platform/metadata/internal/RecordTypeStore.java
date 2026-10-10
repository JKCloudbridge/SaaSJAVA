package app.platform.metadata.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Every statement about the record types of an organization (ADR-0064). Like the rest of the module it runs under the
 * tenant context that was open when the transaction began, and every change carries the version it was based on.
 */
@Repository
class RecordTypeStore {

    /**
     * A record type of the organization; {@code availableFields} is the stored JSON array or null for "every field",
     * {@code picklistValues} the stored JSON object.
     */
    record Row(UUID id, String objectApiName, String apiName, String label, String description, boolean active,
            boolean defaultType, String layoutRef, String availableFields, String picklistValues, long version) {
    }

    private static final String COLUMNS = "id, object_api_name, api_name, label, description, active, is_default, "
            + "layout_ref, cast(available_fields as text) as available_fields, "
            + "cast(picklist_values as text) as picklist_values, version";

    private final JdbcClient jdbc;

    RecordTypeStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Every record type of the organization, grouped by object and in the order they were made. */
    List<Row> all() {
        return jdbc.sql("select " + COLUMNS + " from record_type where deleted_at is null "
                        + "order by lower(object_api_name), created_at, id")
                .query(RecordTypeStore::row).list();
    }

    Optional<Row> find(String objectApiName, String apiName) {
        return jdbc.sql("select " + COLUMNS + " from record_type where object_api_name = :object "
                        + "and api_name = :name and deleted_at is null")
                .param("object", objectApiName).param("name", apiName).query(RecordTypeStore::row).optional();
    }

    boolean nameTaken(String objectApiName, String apiName) {
        return jdbc.sql("select exists (select 1 from record_type where lower(object_api_name) = lower(:object) "
                        + "and lower(api_name) = lower(:name) and deleted_at is null)")
                .param("object", objectApiName).param("name", apiName).query(Boolean.class).single();
    }

    int count(String objectApiName) {
        return jdbc.sql("select count(*) from record_type where object_api_name = :object and deleted_at is null")
                .param("object", objectApiName).query(Integer.class).single();
    }

    Row insert(String objectApiName, String apiName, String label, String description, boolean active,
            boolean defaultType, String availableFields, String picklistValues, UUID actor) {
        return jdbc.sql("insert into record_type (object_api_name, api_name, label, description, active, "
                        + "is_default, available_fields, picklist_values, created_by, updated_by) values (:object, "
                        + ":name, :label, :description, :active, :default, cast(:fields as jsonb), "
                        + "cast(:picklists as jsonb), :actor, :actor) returning " + COLUMNS)
                .param("object", objectApiName).param("name", apiName).param("label", label)
                .param("description", description).param("active", active).param("default", defaultType)
                .param("fields", availableFields).param("picklists", picklistValues).param("actor", actor)
                .query(RecordTypeStore::row).single();
    }

    /** Changes the record type when it still has the version the caller read; false when it does not. */
    boolean update(UUID id, long version, String label, String description, boolean active, boolean defaultType,
            String availableFields, String picklistValues, UUID actor) {
        return jdbc.sql("update record_type set label = :label, description = :description, active = :active, "
                        + "is_default = :default, available_fields = cast(:fields as jsonb), "
                        + "picklist_values = cast(:picklists as jsonb), version = version + 1, updated_by = :actor "
                        + "where id = :id and version = :version and deleted_at is null")
                .param("label", label).param("description", description).param("active", active)
                .param("default", defaultType).param("fields", availableFields).param("picklists", picklistValues)
                .param("actor", actor).param("id", id).param("version", version).update() == 1;
    }

    /** Takes the default mark off every other record type of the object (the database allows one per object). */
    void clearDefaultExcept(String objectApiName, UUID keep, UUID actor) {
        jdbc.sql("update record_type set is_default = false, version = version + 1, updated_by = :actor "
                        + "where object_api_name = :object and is_default and id <> :keep and deleted_at is null")
                .param("actor", actor).param("object", objectApiName).param("keep", keep).update();
    }

    void delete(UUID id, UUID actor) {
        jdbc.sql("update record_type set deleted_at = now(), deleted_by = :actor, is_default = false, "
                        + "version = version + 1, updated_by = :actor where id = :id and deleted_at is null")
                .param("actor", actor).param("id", id).update();
    }

    /** Removes every record type of the object (it is being removed itself); returns how many. */
    int deleteOf(String objectApiName, UUID actor) {
        return jdbc.sql("update record_type set deleted_at = now(), deleted_by = :actor, is_default = false, "
                        + "version = version + 1, updated_by = :actor where object_api_name = :object "
                        + "and deleted_at is null")
                .param("actor", actor).param("object", objectApiName).update();
    }

    private static Row row(ResultSet rs, int row) throws SQLException {
        return new Row(rs.getObject("id", UUID.class), rs.getString("object_api_name"), rs.getString("api_name"),
                rs.getString("label"), rs.getString("description"), rs.getBoolean("active"),
                rs.getBoolean("is_default"), rs.getString("layout_ref"), rs.getString("available_fields"),
                rs.getString("picklist_values"), rs.getLong("version"));
    }
}
