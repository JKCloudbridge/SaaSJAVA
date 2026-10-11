package app.platform.metadata.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Every statement about page layouts, record pages and applications (ADR-0069). The three tables have the same shape
 * (a name, a label, a description and a JSON definition), so one store serves them; the table name comes from the
 * {@link Table} constant and never from a request. Like the rest of the module it runs under the tenant context that
 * was open when the transaction began, and every change carries the version it was based on.
 */
@Repository
class DefinitionStore {

    /** The tables this store reads and writes. */
    enum Table {
        PAGE_LAYOUT("page_layout", true),
        RECORD_PAGE("record_page", true),
        APPLICATION("application", false);

        private final String table;
        private final boolean perObject;

        Table(String table, boolean perObject) {
            this.table = table;
            this.perObject = perObject;
        }

        boolean perObject() {
            return perObject;
        }

        private String columns() {
            return "id, " + (perObject ? "object_api_name" : "'' as object_api_name")
                    + ", api_name, label, description, cast(definition as text) as definition, version";
        }
    }

    /**
     * A stored definition; {@code objectApiName} is empty for an application and {@code definition} is the stored JSON.
     */
    record Row(UUID id, String objectApiName, String apiName, String label, String description, String definition,
            long version) {
    }

    private final JdbcClient jdbc;

    DefinitionStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Every definition of the kind, grouped by object and in the order they were made. */
    List<Row> all(Table table) {
        String order = table.perObject() ? "lower(object_api_name), created_at, id" : "created_at, id";
        return jdbc.sql("select " + table.columns() + " from " + table.table + " where deleted_at is null order by "
                + order).query(DefinitionStore::row).list();
    }

    Optional<Row> find(Table table, String objectApiName, String apiName) {
        if (!table.perObject()) {
            return jdbc.sql("select " + table.columns() + " from " + table.table
                    + " where api_name = :name and deleted_at is null").param("name", apiName)
                    .query(DefinitionStore::row).optional();
        }
        return jdbc.sql("select " + table.columns() + " from " + table.table
                        + " where object_api_name = :object and api_name = :name and deleted_at is null")
                .param("object", objectApiName).param("name", apiName).query(DefinitionStore::row).optional();
    }

    boolean nameTaken(Table table, String objectApiName, String apiName) {
        if (!table.perObject()) {
            return jdbc.sql("select exists (select 1 from " + table.table
                    + " where lower(api_name) = lower(:name) and deleted_at is null)")
                    .param("name", apiName).query(Boolean.class).single();
        }
        return jdbc.sql("select exists (select 1 from " + table.table + " where lower(object_api_name) = "
                        + "lower(:object) and lower(api_name) = lower(:name) and deleted_at is null)")
                .param("object", objectApiName).param("name", apiName).query(Boolean.class).single();
    }

    /** How many there are of the kind on the object (for an application: in the whole organization). */
    int count(Table table, String objectApiName) {
        if (!table.perObject()) {
            return jdbc.sql("select count(*) from " + table.table + " where deleted_at is null")
                    .query(Integer.class).single();
        }
        return jdbc.sql("select count(*) from " + table.table
                        + " where object_api_name = :object and deleted_at is null")
                .param("object", objectApiName).query(Integer.class).single();
    }

    Row insert(Table table, String objectApiName, String apiName, String label, String description,
            String definition, UUID actor) {
        if (!table.perObject()) {
            return jdbc.sql("insert into " + table.table + " (api_name, label, description, definition, created_by, "
                            + "updated_by) values (:name, :label, :description, cast(:definition as jsonb), :actor, "
                            + ":actor) returning " + table.columns())
                    .param("name", apiName).param("label", label).param("description", description)
                    .param("definition", definition).param("actor", actor).query(DefinitionStore::row).single();
        }
        return jdbc.sql("insert into " + table.table + " (object_api_name, api_name, label, description, definition, "
                        + "created_by, updated_by) values (:object, :name, :label, :description, "
                        + "cast(:definition as jsonb), :actor, :actor) returning " + table.columns())
                .param("object", objectApiName).param("name", apiName).param("label", label)
                .param("description", description).param("definition", definition).param("actor", actor)
                .query(DefinitionStore::row).single();
    }

    /** Changes the definition when it still has the version the caller read; false when it does not. */
    boolean update(Table table, UUID id, long version, String label, String description, String definition,
            UUID actor) {
        return jdbc.sql("update " + table.table + " set label = :label, description = :description, "
                        + "definition = cast(:definition as jsonb), version = version + 1, updated_by = :actor "
                        + "where id = :id and version = :version and deleted_at is null")
                .param("label", label).param("description", description).param("definition", definition)
                .param("actor", actor).param("id", id).param("version", version).update() == 1;
    }

    void delete(Table table, UUID id, UUID actor) {
        jdbc.sql("update " + table.table + " set deleted_at = now(), deleted_by = :actor, version = version + 1, "
                        + "updated_by = :actor where id = :id and deleted_at is null")
                .param("actor", actor).param("id", id).update();
    }

    private static Row row(ResultSet rs, int row) throws SQLException {
        return new Row(rs.getObject("id", UUID.class), rs.getString("object_api_name"), rs.getString("api_name"),
                rs.getString("label"), rs.getString("description"), rs.getString("definition"),
                rs.getLong("version"));
    }
}
