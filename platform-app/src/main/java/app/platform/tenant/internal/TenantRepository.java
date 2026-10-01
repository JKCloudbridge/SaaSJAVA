package app.platform.tenant.internal;

import app.platform.sharedkernel.ActorId;
import app.platform.sharedkernel.TenantId;
import app.platform.tenant.Tenant;
import app.platform.tenant.TenantSlug;
import app.platform.tenant.TenantStatus;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The tenant table. Not tenant-scoped (no row level security), so it can be read before a context exists. */
@Repository
class TenantRepository {

    private static final String COLUMNS = "id, slug, display_name, status, status_changed_at, version";

    private final JdbcClient jdbc;

    TenantRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void insert(TenantId id, TenantSlug slug, String displayName, ActorId actor) {
        jdbc.sql("insert into tenant (id, slug, display_name, created_by, updated_by) "
                        + "values (:id, :slug, :name, :actor, :actor)")
                .param("id", id.value())
                .param("slug", slug.value())
                .param("name", displayName)
                .param("actor", actor.value())
                .update();
    }

    /** Reads and locks the row, so two concurrent moves of one tenant are decided one after the other. */
    Optional<Tenant> findForUpdate(TenantId id) {
        return jdbc.sql("select " + COLUMNS + " from tenant where id = :id and deleted_at is null for update")
                .param("id", id.value())
                .query(TenantRepository::map)
                .optional();
    }

    Optional<Tenant> findById(TenantId id) {
        return jdbc.sql("select " + COLUMNS + " from tenant where id = :id and deleted_at is null")
                .param("id", id.value())
                .query(TenantRepository::map)
                .optional();
    }

    Optional<Tenant> findBySlug(TenantSlug slug) {
        return jdbc.sql("select " + COLUMNS + " from tenant where slug = :slug and deleted_at is null")
                .param("slug", slug.value())
                .query(TenantRepository::map)
                .optional();
    }

    /** @return whether a row was changed; false means the version was stale */
    boolean updateStatus(Tenant current, TenantStatus target, ActorId actor) {
        return jdbc.sql("update tenant set status = :status, updated_by = :actor, version = version + 1 "
                        + "where id = :id and version = :version")
                .param("status", target.name())
                .param("actor", actor.value())
                .param("id", current.id().value())
                .param("version", current.version())
                .update() == 1;
    }

    private static Tenant map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new Tenant(
                new TenantId(rs.getObject("id", UUID.class)),
                new TenantSlug(rs.getString("slug")),
                rs.getString("display_name"),
                TenantStatus.valueOf(rs.getString("status")),
                rs.getObject("status_changed_at", OffsetDateTime.class).toInstant(),
                rs.getLong("version"));
    }
}
