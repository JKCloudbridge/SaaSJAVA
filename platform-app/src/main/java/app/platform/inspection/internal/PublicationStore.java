package app.platform.inspection.internal;

import app.platform.tenant.TenantContexts;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The memory of publishing a template to a group (FA4, ADR-0188): one row for each group a template is or was published
 * to. Every statement is a constant with parameters; the organization is a parameter taken from the tenant context in
 * addition to the forced row level security of the table. What may be published, to whom and by whom is decided by
 * {@link TemplatePublishing}, not here; the database refuses a delete, a change of what a row says and a change of a
 * revoked row whatever is asked (V066).
 */
@Repository
class PublicationStore {

    /**
     * One publication.
     *
     * @param id its identifier
     * @param templateId the template
     * @param versionNo the version that was in use for the group when it was last made current
     * @param location the location code the group stood for
     * @param department the department code the group stood for
     * @param groupId the group
     * @param madeShare whether the publish made the read share itself (false: the group already had a share, which
     *        taking the template back leaves alone)
     * @param publishedAt when the group was first given the template
     * @param publishedBy who gave it
     */
    record Row(UUID id, UUID templateId, int versionNo, String location, String department, UUID groupId,
            boolean madeShare, Instant publishedAt, UUID publishedBy) {

        /** Prints the identity only: the codes are the organization's text. */
        @Override
        public String toString() {
            return "Row[" + id + "]";
        }
    }

    private static final String COLUMNS = "id, template_id, version_no, location, department, group_id, made_share, "
            + "created_at, created_by";

    private static final String LIVE = "tenant_id = :tenant and template_id = :template and revoked_at is null "
            + "and deleted_at is null";

    private final JdbcClient jdbc;
    private final TenantContexts contexts;

    PublicationStore(JdbcClient jdbc, TenantContexts contexts) {
        this.jdbc = jdbc;
        this.contexts = contexts;
    }

    /**
     * Takes the lock that serializes the publishing, unpublishing and retiring of one template, so that two requests
     * arriving together (two instances) are decided one after the other. Held until the transaction ends.
     */
    void lock(UUID templateId) {
        jdbc.sql("select pg_advisory_xact_lock(hashtextextended(:key, 0))")
                .param("key", "publication:" + tenant() + ":" + templateId).query().listOfRows();
    }

    /** The groups the template is published to now, oldest first. */
    List<Row> live(UUID templateId) {
        return jdbc.sql("select " + COLUMNS + " from inspection_publication where " + LIVE
                        + " order by created_at, id")
                .param("tenant", tenant()).param("template", templateId).query(PublicationStore::map).list();
    }

    Row insert(UUID templateId, int versionNo, String location, String department, UUID groupId, boolean madeShare,
            UUID actor) {
        return jdbc.sql("insert into inspection_publication (tenant_id, template_id, version_no, location, department, "
                        + "group_id, made_share, created_by, updated_by) values (:tenant, :template, :version, "
                        + ":location, :department, :group, :made, :actor, :actor) returning " + COLUMNS)
                .param("tenant", tenant()).param("template", templateId).param("version", versionNo)
                .param("location", location).param("department", department).param("group", groupId)
                .param("made", madeShare).param("actor", actor).query(PublicationStore::map).single();
    }

    /** The version a group's publication points at moves on when a newer version is published for the same people. */
    void moveVersion(UUID id, int versionNo, UUID actor) {
        jdbc.sql("update inspection_publication set version_no = :version, version = version + 1, "
                        + "updated_by = :actor where id = :id and revoked_at is null and deleted_at is null")
                .param("version", versionNo).param("actor", actor).param("id", id).update();
    }

    /** Ends a publication; it never changes again. */
    void revoke(UUID id, String reason, UUID actor) {
        jdbc.sql("update inspection_publication set revoked_at = now(), revoked_by = :actor, ended_reason = :reason, "
                        + "version = version + 1, updated_by = :actor where id = :id and revoked_at is null "
                        + "and deleted_at is null")
                .param("actor", actor).param("reason", reason).param("id", id).update();
    }

    private UUID tenant() {
        return contexts.require().tenantId().value();
    }

    private static Row map(ResultSet rs, int row) throws SQLException {
        OffsetDateTime at = rs.getObject("created_at", OffsetDateTime.class);
        return new Row(rs.getObject("id", UUID.class), rs.getObject("template_id", UUID.class),
                rs.getInt("version_no"), rs.getString("location"), rs.getString("department"),
                rs.getObject("group_id", UUID.class), rs.getBoolean("made_share"), at.toInstant(),
                rs.getObject("created_by", UUID.class));
    }
}
