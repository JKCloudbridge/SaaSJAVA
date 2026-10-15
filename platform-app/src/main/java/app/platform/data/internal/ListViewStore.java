package app.platform.data.internal;

import app.platform.tenant.TenantContexts;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The saved list views (Sprint 19, ADR-0130): one row per view. Every statement is a constant with parameters; the
 * organization is a parameter taken from the tenant context in addition to the forced row level security of the table.
 * What a person may see or change is decided by {@link ListViews}, not here: this class does what it is told.
 */
@Repository
class ListViewStore {

    /** A view as stored; the definition is the JSON text the database holds. */
    record Row(UUID id, String objectApiName, String label, String scope, String definition, long version,
            Instant updatedAt, UUID createdBy) {

        /** Prints the identity only: the label is text a person chose. */
        @Override
        public String toString() {
            return "ListViewStore.Row[" + objectApiName + " " + id + " v" + version + "]";
        }
    }

    private static final String COLUMNS = "id, object_api_name, label, scope, definition::text as definition, version, "
            + "updated_at, created_by";

    private static final String INSERT = "insert into list_view (tenant_id, object_api_name, label, scope, "
            + "definition, created_by, updated_by) values (:tenant, :object, :label, :scope, "
            + "cast(:definition as jsonb), :actor, :actor) returning " + COLUMNS;

    // The version in the where clause is the optimistic check: nothing changes when it is stale; the row guard asks
    // for the version to rise by exactly one.
    private static final String UPDATE = "update list_view set label = :label, scope = :scope, "
            + "definition = cast(:definition as jsonb), version = version + 1, updated_by = :actor "
            + "where tenant_id = :tenant and object_api_name = :object and id = :id and version = :version "
            + "and deleted_at is null returning " + COLUMNS;

    private static final String DELETE = "update list_view set deleted_at = now(), deleted_by = :actor, "
            + "version = version + 1, updated_by = :actor where tenant_id = :tenant and object_api_name = :object "
            + "and id = :id and version = :version and deleted_at is null";

    private final JdbcClient jdbc;
    private final TenantContexts contexts;

    ListViewStore(JdbcClient jdbc, TenantContexts contexts) {
        this.jdbc = jdbc;
        this.contexts = contexts;
    }

    /** The views of the object this person sees: every shared one and their own personal ones. */
    List<Row> visibleTo(String object, UUID user) {
        return jdbc.sql("select " + COLUMNS + " from list_view where tenant_id = :tenant and object_api_name = :object "
                        + "and deleted_at is null and (scope = 'SHARED' or created_by = :user) "
                        + "order by case scope when 'SHARED' then 0 else 1 end, lower(label), id")
                .param("tenant", tenant()).param("object", object).param("user", user).query(ListViewStore::map)
                .list();
    }

    Optional<Row> find(String object, UUID id) {
        return jdbc.sql("select " + COLUMNS + " from list_view where tenant_id = :tenant and object_api_name = :object "
                        + "and id = :id and deleted_at is null")
                .param("tenant", tenant()).param("object", object).param("id", id).query(ListViewStore::map)
                .optional();
    }

    long count(String object, String scope, UUID user) {
        return jdbc.sql("select count(*) from list_view where tenant_id = :tenant and object_api_name = :object "
                        + "and scope = :scope and deleted_at is null and (scope = 'SHARED' or created_by = :user)")
                .param("tenant", tenant()).param("object", object).param("scope", scope).param("user", user)
                .query(Long.class).single();
    }

    Row insert(String object, String label, String scope, String definition, UUID actor) {
        return jdbc.sql(INSERT).param("tenant", tenant()).param("object", object).param("label", label)
                .param("scope", scope).param("definition", definition).param("actor", actor)
                .query(ListViewStore::map).single();
    }

    /** The changed view, or empty when the version was stale (or the view is gone). */
    Optional<Row> update(String object, UUID id, long version, String label, String scope, String definition,
            UUID actor) {
        return jdbc.sql(UPDATE).param("tenant", tenant()).param("object", object).param("id", id)
                .param("version", version).param("label", label).param("scope", scope)
                .param("definition", definition).param("actor", actor).query(ListViewStore::map).optional();
    }

    /** Removes the view (soft delete); false when the version was stale or the view is gone. */
    boolean delete(String object, UUID id, long version, UUID actor) {
        return jdbc.sql(DELETE).param("tenant", tenant()).param("object", object).param("id", id)
                .param("version", version).param("actor", actor).update() == 1;
    }

    private UUID tenant() {
        return contexts.require().tenantId().value();
    }

    private static Row map(ResultSet rs, int row) throws SQLException {
        OffsetDateTime at = rs.getObject("updated_at", OffsetDateTime.class);
        return new Row(rs.getObject("id", UUID.class), rs.getString("object_api_name"), rs.getString("label"),
                rs.getString("scope"), rs.getString("definition"), rs.getLong("version"), at.toInstant(),
                rs.getObject("created_by", UUID.class));
    }
}
