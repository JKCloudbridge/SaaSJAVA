package app.platform.metadata.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Every statement about the organization's own values of a platform-defined picklist (FA2A, ADR-0182). Like the rest
 * of the module it runs under the tenant context that was open when the transaction began: the database shows only the
 * organization's rows, and a row it inserts belongs to the organization by default. The text of a row is the JSON the
 * configuration of a picklist is stored as ({@link ConfigCodec}); {@code {}} means "the platform's starting list".
 */
@Repository
class OrganizationValuesStore {

    /** The EMPTY text: nothing of the platform's list is overridden. */
    static final String PLATFORM_LIST = "{}";

    /** One row; {@code config} is the stored text and {@code version} the row's own (0 for the first). */
    record Row(UUID id, String objectApiName, String fieldApiName, String config, long version) {
    }

    private static final String COLUMNS = "id, object_api_name, field_api_name, "
            + "cast(config as text) as config, version";

    private final JdbcClient jdbc;

    OrganizationValuesStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Every live row of the organization (read once when the snapshot is built). */
    List<Row> all() {
        return jdbc.sql("select " + COLUMNS + " from organization_field_values where deleted_at is null "
                        + "order by object_api_name, field_api_name")
                .query((rs, row) -> new Row(rs.getObject("id", UUID.class), rs.getString("object_api_name"),
                        rs.getString("field_api_name"), rs.getString("config"), rs.getLong("version")))
                .list();
    }

    /** The row of one field, locked until the transaction ends so that two additions to one list queue up. */
    Optional<Row> lock(String objectApiName, String fieldApiName) {
        return jdbc.sql("select " + COLUMNS + " from organization_field_values where object_api_name = :object "
                        + "and field_api_name = :field and deleted_at is null for update")
                .param("object", objectApiName).param("field", fieldApiName)
                .query((rs, row) -> new Row(rs.getObject("id", UUID.class), rs.getString("object_api_name"),
                        rs.getString("field_api_name"), rs.getString("config"), rs.getLong("version")))
                .optional();
    }

    /** Makes the first row of the field; false when another transaction made it first. */
    boolean insert(String objectApiName, String fieldApiName, String config, UUID actor) {
        return jdbc.sql("insert into organization_field_values (object_api_name, field_api_name, config, created_by, "
                        + "updated_by) values (:object, :field, cast(:config as jsonb), :actor, :actor) "
                        + "on conflict (tenant_id, object_api_name, field_api_name) where deleted_at is null "
                        + "do nothing")
                .param("object", objectApiName).param("field", fieldApiName).param("config", config)
                .param("actor", actor).update() == 1;
    }

    /** Changes the row when it still has the version the caller read; false when it does not. */
    boolean update(UUID id, long version, String config, UUID actor) {
        return jdbc.sql("update organization_field_values set config = cast(:config as jsonb), "
                        + "version = version + 1, updated_by = :actor "
                        + "where id = :id and version = :version and deleted_at is null")
                .param("config", config).param("actor", actor).param("id", id).param("version", version)
                .update() == 1;
    }
}
