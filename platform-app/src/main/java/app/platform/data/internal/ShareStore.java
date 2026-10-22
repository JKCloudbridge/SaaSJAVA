package app.platform.data.internal;

import app.platform.tenant.TenantContexts;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The explicit shares of single records (Sprint 17, ADR-0117): one row per record and grantee (a user, a public
 * group, a role alone or a role with the roles below it). Every statement is a constant with parameters; the
 * organization is a parameter taken from the tenant context in addition to the forced row level security of the
 * table. A share is read by the statements of a list (see {@code JsonbQuerySql}) as a set of record identifiers; this
 * class is for managing them.
 */
@Repository
class ShareStore {

    /** A share as stored. */
    record ShareRow(UUID id, UUID recordId, String granteeType, UUID granteeId, int level, java.time.Instant sharedAt,
            UUID sharedBy) {
    }

    private static final String COLUMNS = "id, record_id, grantee_type, grantee_id, access_level, updated_at, "
            + "updated_by";

    // The second share of a record with the same grantee changes the level of the first (one row per record and
    // grantee), and for a role also the kind (alone, or with the roles below); the row guard asks for the version to
    // rise by exactly one and allows no other change of the kind.
    private static final String UPSERT = "insert into record_share (tenant_id, object_api_name, record_id, "
            + "grantee_type, grantee_id, access_level, created_by, updated_by) values (:tenant, :object, :record, "
            + ":type, :grantee, :level, :actor, :actor) on conflict (tenant_id, record_id, grantee_id) "
            + "where deleted_at is null do update set access_level = excluded.access_level, "
            + "grantee_type = excluded.grantee_type, version = record_share.version + 1, "
            + "updated_by = excluded.updated_by returning " + COLUMNS;

    // A share made for the platform's own act never changes one that exists: the row that is there stays as it is, so
    // the level someone chose by hand is never lowered between the caller's check and its insert (FA4, ADR-0188).
    private static final String INSERT_ABSENT = "insert into record_share (tenant_id, object_api_name, record_id, "
            + "grantee_type, grantee_id, access_level, created_by, updated_by) values (:tenant, :object, :record, "
            + ":type, :grantee, :level, :actor, :actor) on conflict (tenant_id, record_id, grantee_id) "
            + "where deleted_at is null do nothing returning id";

    private final JdbcClient jdbc;
    private final TenantContexts contexts;

    ShareStore(JdbcClient jdbc, TenantContexts contexts) {
        this.jdbc = jdbc;
        this.contexts = contexts;
    }

    List<ShareRow> shares(UUID recordId) {
        return jdbc.sql("select " + COLUMNS + " from record_share where tenant_id = :tenant and record_id = :record "
                        + "and deleted_at is null order by created_at, id")
                .param("tenant", tenant()).param("record", recordId).query(ShareStore::map).list();
    }

    Optional<ShareRow> share(UUID recordId, UUID granteeId) {
        return jdbc.sql("select " + COLUMNS + " from record_share where tenant_id = :tenant and record_id = :record "
                        + "and grantee_id = :grantee and deleted_at is null")
                .param("tenant", tenant()).param("record", recordId).param("grantee", granteeId)
                .query(ShareStore::map).optional();
    }

    int count(UUID recordId) {
        return jdbc.sql("select count(*) from record_share where tenant_id = :tenant and record_id = :record "
                        + "and deleted_at is null")
                .param("tenant", tenant()).param("record", recordId).query(Integer.class).single();
    }

    ShareRow upsert(String object, UUID recordId, String granteeType, UUID granteeId, int level, UUID actor) {
        return jdbc.sql(UPSERT).param("tenant", tenant()).param("object", object).param("record", recordId)
                .param("type", granteeType).param("grantee", granteeId).param("level", level).param("actor", actor)
                .query(ShareStore::map).single();
    }

    /** Makes the share unless the grantee has one; whether it was made. */
    boolean insertAbsent(String object, UUID recordId, String granteeType, UUID granteeId, int level, UUID actor) {
        return !jdbc.sql(INSERT_ABSENT).param("tenant", tenant()).param("object", object).param("record", recordId)
                .param("type", granteeType).param("grantee", granteeId).param("level", level).param("actor", actor)
                .query(UUID.class).list().isEmpty();
    }

    /** Takes the share back: the row is deleted (the audit trail keeps the history). */
    boolean delete(UUID recordId, UUID granteeId) {
        return jdbc.sql("delete from record_share where tenant_id = :tenant and record_id = :record "
                        + "and grantee_id = :grantee")
                .param("tenant", tenant()).param("record", recordId).param("grantee", granteeId).update() > 0;
    }

    /**
     * Ends every share addressed to a removed role or group, on every record of the organization; the number ended.
     * The identifiers of users, groups and roles never collide, and the kinds make the intent explicit.
     */
    int deleteGrantee(UUID granteeId, List<String> types) {
        return jdbc.sql("delete from record_share where tenant_id = :tenant and grantee_id = :grantee "
                        + "and grantee_type in (:types)")
                .param("tenant", tenant()).param("grantee", granteeId).param("types", types).update();
    }

    private UUID tenant() {
        return contexts.require().tenantId().value();
    }

    private static ShareRow map(ResultSet rs, int row) throws SQLException {
        OffsetDateTime at = rs.getObject("updated_at", OffsetDateTime.class);
        return new ShareRow(rs.getObject("id", UUID.class), rs.getObject("record_id", UUID.class),
                rs.getString("grantee_type"), rs.getObject("grantee_id", UUID.class), rs.getInt("access_level"),
                at.toInstant(), rs.getObject("updated_by", UUID.class));
    }
}
