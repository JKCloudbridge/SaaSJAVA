package app.platform.platformadmin.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The support-access grants (ADR-0035). Tenant-scoped: every statement runs under the tenant context that was open when
 * the transaction began, and row level security refuses anything else, so a grant of another organization is never
 * found.
 * Times are the database clock's ({@code now()}), the same clock the database guard of the lifecycle uses, so "active"
 * and "expired" mean the same thing everywhere.
 */
@Repository
class SupportAccessRepository {

    /** How long a request waits for an answer. */
    static final String REQUEST_LIFE = "24 hours";

    /**
     * A grant with the facts the screens need.
     *
     * @param requester the display name of the platform person (never the address)
     * @param active whether the access can be used right now
     * @param ended whether an approved window has ended, or an unanswered request ran out
     */
    record Grant(UUID id, UUID requestedBy, String requester, String reason, int requestedMinutes, String status,
            Instant requestedAt, Instant accessExpiresAt, boolean active, boolean ended) {
    }

    private static final String SELECT = "select g.id, g.requested_by, u.display_name, g.reason, "
            + "g.requested_minutes, g.status, g.created_at, g.access_expires_at, "
            + "(g.status = 'APPROVED' and g.access_expires_at > now()) as active, "
            + "((g.status = 'APPROVED' and g.access_expires_at <= now()) "
            + "or (g.status = 'REQUESTED' and g.request_expires_at <= now())) as ended "
            + "from support_access_grant g join platform_user u on u.id = g.requested_by "
            + "where g.deleted_at is null";

    private final JdbcClient jdbc;

    SupportAccessRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Records a request. A second open request of the same person raises {@code DuplicateKeyException}. */
    UUID insertRequest(UUID requestedBy, String reason, int minutes) {
        return jdbc.sql("insert into support_access_grant (requested_by, reason, requested_minutes, "
                        + "request_expires_at, created_by, updated_by) "
                        + "values (:by, :reason, :minutes, now() + interval '" + REQUEST_LIFE + "', :by, :by) "
                        + "returning id")
                .param("by", requestedBy)
                .param("reason", reason)
                .param("minutes", minutes)
                .query(UUID.class)
                .single();
    }

    /**
     * Closes the person's requests that nobody answered in time, so that an old unanswered request never blocks a new
     * one (there is one open request per person and organization).
     */
    void cancelStale(UUID by) {
        jdbc.sql("update support_access_grant set status = 'CANCELLED', version = version + 1, updated_by = :by "
                        + "where requested_by = :by and status = 'REQUESTED' and request_expires_at <= now() "
                        + "and deleted_at is null")
                .param("by", by)
                .update();
    }

    /** The newest grants of the current organization. */
    List<Grant> list() {
        return jdbc.sql(SELECT + " order by g.created_at desc, g.id limit 100")
                .query(SupportAccessRepository::grant)
                .list();
    }

    /** A grant of the current organization, locked until the transaction ends. */
    Optional<Grant> findForUpdate(UUID id) {
        return jdbc.sql(SELECT + " and g.id = :id for update of g")
                .param("id", id)
                .query(SupportAccessRepository::grant)
                .optional();
    }

    /** Opens the window: now plus the minutes, decided by the database clock. */
    void approve(UUID id, UUID by, int minutes) {
        jdbc.sql("update support_access_grant set status = 'APPROVED', decided_by = :by, decided_at = now(), "
                        + "access_expires_at = now() + make_interval(mins => :minutes), "
                        + "version = version + 1, updated_by = :by where id = :id and status = 'REQUESTED'")
                .param("by", by)
                .param("minutes", minutes)
                .param("id", id)
                .update();
    }

    void deny(UUID id, UUID by) {
        jdbc.sql("update support_access_grant set status = 'DENIED', decided_by = :by, decided_at = now(), "
                        + "version = version + 1, updated_by = :by where id = :id and status = 'REQUESTED'")
                .param("by", by)
                .param("id", id)
                .update();
    }

    void cancel(UUID id, UUID by) {
        jdbc.sql("update support_access_grant set status = 'CANCELLED', version = version + 1, updated_by = :by "
                        + "where id = :id and status = 'REQUESTED'")
                .param("by", by)
                .param("id", id)
                .update();
    }

    void revoke(UUID id, UUID by) {
        jdbc.sql("update support_access_grant set status = 'REVOKED', revoked_by = :by, revoked_at = now(), "
                        + "version = version + 1, updated_by = :by where id = :id and status = 'APPROVED'")
                .param("by", by)
                .param("id", id)
                .update();
    }

    /** Whether the person has an approved window that is still open, in the current organization. */
    boolean hasActive(UUID platformUser) {
        return jdbc.sql("select count(*) from support_access_grant where requested_by = :user "
                        + "and status = 'APPROVED' and access_expires_at > now() and deleted_at is null")
                .param("user", platformUser)
                .query(Long.class)
                .single() > 0;
    }

    private static Grant grant(ResultSet rs, int row) throws SQLException {
        return new Grant(rs.getObject("id", UUID.class), rs.getObject("requested_by", UUID.class),
                rs.getString("display_name"), rs.getString("reason"), rs.getInt("requested_minutes"),
                rs.getString("status"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("access_expires_at") == null ? null : rs.getTimestamp("access_expires_at").toInstant(),
                rs.getBoolean("active"), rs.getBoolean("ended"));
    }
}
