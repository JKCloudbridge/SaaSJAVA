package app.platform.metadata.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Every statement about the object and field definitions of an organization (ADR-0058). Like the rest of the platform
 * it runs under the tenant context that was open when the transaction began: the database shows only the organization's
 * own rows, and a row it inserts belongs to the organization by default. Every change carries the version it was
 * based on, and a change on top of a newer version changes nothing.
 */
@Repository
class MetadataStore {

    /** An object of the organization. */
    record ObjectRow(UUID id, String apiName, String label, String pluralLabel, String description, long version) {
    }

    /** A field of the organization; {@code config} is the stored settings text. */
    record FieldRow(UUID id, String objectApiName, String apiName, String label, String description, String dataType,
            boolean required, boolean unique, String defaultValue, String config, int sortOrder, long version) {
    }

    private static final String OBJECT_COLUMNS = "id, api_name, label, plural_label, description, version";
    private static final String FIELD_COLUMNS = "id, object_api_name, api_name, label, description, data_type, "
            + "required, is_unique, default_value, cast(config as text) as config, sort_order, version";

    private final JdbcClient jdbc;

    MetadataStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ---- objects ----

    List<ObjectRow> objects() {
        return jdbc.sql("select " + OBJECT_COLUMNS + " from object_definition where deleted_at is null "
                        + "order by lower(api_name)")
                .query(MetadataStore::objectRow).list();
    }

    Optional<ObjectRow> object(String apiName) {
        return jdbc.sql("select " + OBJECT_COLUMNS + " from object_definition where api_name = :name "
                        + "and deleted_at is null")
                .param("name", apiName).query(MetadataStore::objectRow).optional();
    }

    /** Whether an object with this name exists, without regard to upper or lower case. */
    boolean objectNameTaken(String apiName) {
        return jdbc.sql("select exists (select 1 from object_definition where lower(api_name) = lower(:name) "
                        + "and deleted_at is null)")
                .param("name", apiName).query(Boolean.class).single();
    }

    int countObjects() {
        return jdbc.sql("select count(*) from object_definition where deleted_at is null")
                .query(Integer.class).single();
    }

    ObjectRow insertObject(String apiName, String label, String pluralLabel, String description, UUID actor) {
        return jdbc.sql("insert into object_definition (api_name, label, plural_label, description, created_by, "
                        + "updated_by) values (:name, :label, :plural, :description, :actor, :actor) returning "
                        + OBJECT_COLUMNS)
                .param("name", apiName).param("label", label).param("plural", pluralLabel)
                .param("description", description).param("actor", actor)
                .query(MetadataStore::objectRow).single();
    }

    /** Changes the labels when the row still has the version the caller read; false when it does not. */
    boolean updateObject(UUID id, long version, String label, String pluralLabel, String description, UUID actor) {
        return jdbc.sql("update object_definition set label = :label, plural_label = :plural, "
                        + "description = :description, version = version + 1, updated_by = :actor "
                        + "where id = :id and version = :version and deleted_at is null")
                .param("label", label).param("plural", pluralLabel).param("description", description)
                .param("actor", actor).param("id", id).param("version", version).update() == 1;
    }

    void deleteObject(UUID id, UUID actor) {
        jdbc.sql("update object_definition set deleted_at = now(), deleted_by = :actor, version = version + 1, "
                        + "updated_by = :actor where id = :id and deleted_at is null")
                .param("actor", actor).param("id", id).update();
    }

    // ---- fields ----

    /** Every field of the organization, grouped by object and in the order they were added. */
    List<FieldRow> fields() {
        return jdbc.sql("select " + FIELD_COLUMNS + " from field_definition where deleted_at is null "
                        + "order by lower(object_api_name), sort_order, created_at")
                .query(MetadataStore::fieldRow).list();
    }

    Optional<FieldRow> field(String objectApiName, String apiName) {
        return jdbc.sql("select " + FIELD_COLUMNS + " from field_definition where object_api_name = :object "
                        + "and api_name = :name and deleted_at is null")
                .param("object", objectApiName).param("name", apiName).query(MetadataStore::fieldRow).optional();
    }

    /** Whether the object has a field with this name, without regard to upper or lower case. */
    boolean fieldNameTaken(String objectApiName, String apiName) {
        return jdbc.sql("select exists (select 1 from field_definition where lower(object_api_name) = "
                        + "lower(:object) and lower(api_name) = lower(:name) and deleted_at is null)")
                .param("object", objectApiName).param("name", apiName).query(Boolean.class).single();
    }

    int countFields(String objectApiName) {
        return jdbc.sql("select count(*) from field_definition where object_api_name = :object "
                        + "and deleted_at is null")
                .param("object", objectApiName).query(Integer.class).single();
    }

    int countMasterDetails(String objectApiName) {
        return jdbc.sql("select count(*) from field_definition where object_api_name = :object "
                        + "and data_type = 'MASTER_DETAIL' and deleted_at is null")
                .param("object", objectApiName).query(Integer.class).single();
    }

    int nextSortOrder(String objectApiName) {
        return jdbc.sql("select coalesce(max(sort_order), 0) + 10 from field_definition "
                        + "where object_api_name = :object and deleted_at is null")
                .param("object", objectApiName).query(Integer.class).single();
    }

    FieldRow insertField(String objectApiName, String apiName, String label, String description, String dataType,
            boolean required, boolean unique, String defaultValue, String config, int sortOrder, UUID actor) {
        return jdbc.sql("insert into field_definition (object_api_name, api_name, label, description, data_type, "
                        + "required, is_unique, default_value, config, sort_order, created_by, updated_by) "
                        + "values (:object, :name, :label, :description, :type, :required, :unique, :default, "
                        + "cast(:config as jsonb), :sort, :actor, :actor) returning " + FIELD_COLUMNS)
                .param("object", objectApiName).param("name", apiName).param("label", label)
                .param("description", description).param("type", dataType).param("required", required)
                .param("unique", unique).param("default", defaultValue).param("config", config)
                .param("sort", sortOrder).param("actor", actor)
                .query(MetadataStore::fieldRow).single();
    }

    /** Changes the field when it still has the version the caller read; false when it does not. */
    boolean updateField(UUID id, long version, String label, String description, boolean required, boolean unique,
            String defaultValue, String config, UUID actor) {
        return jdbc.sql("update field_definition set label = :label, description = :description, "
                        + "required = :required, is_unique = :unique, default_value = :default, "
                        + "config = cast(:config as jsonb), version = version + 1, updated_by = :actor "
                        + "where id = :id and version = :version and deleted_at is null")
                .param("label", label).param("description", description).param("required", required)
                .param("unique", unique).param("default", defaultValue).param("config", config)
                .param("actor", actor).param("id", id).param("version", version).update() == 1;
    }

    void deleteField(UUID id, UUID actor) {
        jdbc.sql("update field_definition set deleted_at = now(), deleted_by = :actor, version = version + 1, "
                        + "updated_by = :actor where id = :id and deleted_at is null")
                .param("actor", actor).param("id", id).update();
    }

    /** Removes every field of the object (it is being removed itself); returns how many. */
    int deleteFieldsOf(String objectApiName, UUID actor) {
        return jdbc.sql("update field_definition set deleted_at = now(), deleted_by = :actor, "
                        + "version = version + 1, updated_by = :actor where object_api_name = :object "
                        + "and deleted_at is null")
                .param("actor", actor).param("object", objectApiName).update();
    }

    /**
     * The lookup and master-detail fields of OTHER custom objects, and of standard ones, that point to the object, as
     * "Object.field" texts. A custom object cannot be removed while any exists.
     */
    List<String> referencesTo(String targetObject) {
        return jdbc.sql("select object_api_name || '.' || api_name from field_definition "
                        + "where data_type in ('LOOKUP', 'MASTER_DETAIL') and config ->> 'targetObject' = :target "
                        + "and object_api_name <> :target and deleted_at is null order by 1")
                .param("target", targetObject).query(String.class).list();
    }

    // ---- row mapping ----

    private static ObjectRow objectRow(ResultSet rs, int row) throws SQLException {
        return new ObjectRow(rs.getObject("id", UUID.class), rs.getString("api_name"), rs.getString("label"),
                rs.getString("plural_label"), rs.getString("description"), rs.getLong("version"));
    }

    private static FieldRow fieldRow(ResultSet rs, int row) throws SQLException {
        return new FieldRow(rs.getObject("id", UUID.class), rs.getString("object_api_name"),
                rs.getString("api_name"), rs.getString("label"), rs.getString("description"),
                rs.getString("data_type"), rs.getBoolean("required"), rs.getBoolean("is_unique"),
                rs.getString("default_value"), rs.getString("config"), rs.getInt("sort_order"),
                rs.getLong("version"));
    }
}
