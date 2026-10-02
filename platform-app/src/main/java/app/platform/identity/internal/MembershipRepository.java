package app.platform.identity.internal;

import app.platform.sharedkernel.ActorId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The membership table (ADR-0025, ADR-0026). Tenant-scoped: every statement runs under the tenant context that was open
 * when the transaction began, and row level security refuses anything else. The tenant is never passed in; the column
 * default and the policy take it from the transaction. The database also enforces the lifecycle (legal status moves)
 * and that the organization keeps a member who can manage access (ADR-0044), so a bug here cannot break those rules.
 *
 * <p>The administrator marker column of Sprint 5 still exists but nothing reads or writes it any more (ADR-0039): what
 * a
 * member may do comes from their profile, access policies and grants. A later contract migration drops it.
 */
@Repository
class MembershipRepository {

    /** The statuses a membership can have. */
    static final String ACTIVE = "ACTIVE";
    static final String DEACTIVATED = "DEACTIVATED";

    /**
     * A member as the people who may see members see them.
     *
     * @param founding the historical fact "this person created the organization"; grants nothing
     */
    record Member(UUID id, UUID userId, String email, String displayName, String status, boolean founding,
            Instant since) {
    }

    /** The membership of one user in the current organization. */
    record Own(UUID id, UUID userId, String status) {

        boolean active() {
            return ACTIVE.equals(status);
        }
    }

    private static final String MEMBER_COLUMNS = "m.id, m.user_id, u.email, u.display_name, m.status, "
            + "m.founding_administrator, m.created_at";

    private final JdbcClient jdbc;

    MembershipRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Makes the user a member of the current tenant and records the historical fact that they created the organization.
     * The founder's abilities come from the administrator profile the caller gives them next.
     */
    UUID insertFounder(UUID userId, ActorId actor) {
        return insert(userId, true, actor);
    }

    /** Makes the user a member of the current tenant. */
    UUID insertMember(UUID userId, boolean founding, ActorId actor) {
        return insert(userId, founding, actor);
    }

    private UUID insert(UUID userId, boolean founding, ActorId actor) {
        return jdbc.sql("insert into membership (user_id, founding_administrator, created_by, updated_by) "
                        + "values (:user, :founding, :actor, :actor) returning id")
                .param("user", userId)
                .param("founding", founding)
                .param("actor", actor.value())
                .query(UUID.class)
                .single();
    }

    /** The user's membership of the current tenant, in any status. */
    Optional<Own> findOwn(UUID userId) {
        return jdbc.sql("select id, user_id, status from membership where user_id = :user and deleted_at is null")
                .param("user", userId)
                .query(MembershipRepository::own)
                .optional();
    }

    /** Whether the user has any live membership of the current tenant (active or deactivated). */
    boolean exists(UUID userId) {
        return jdbc.sql("select count(*) from membership where user_id = :user and deleted_at is null")
                .param("user", userId)
                .query(Long.class)
                .single() > 0;
    }

    /** Every member of the current tenant, newest first. */
    List<Member> list() {
        return jdbc.sql("select " + MEMBER_COLUMNS + " from membership m join platform_user u on u.id = m.user_id "
                        + "where m.deleted_at is null order by m.created_at desc, m.id")
                .query(MembershipRepository::member)
                .list();
    }

    /** A member of the current tenant by membership identifier. */
    Optional<Member> find(UUID membershipId) {
        return jdbc.sql("select " + MEMBER_COLUMNS + " from membership m join platform_user u on u.id = m.user_id "
                        + "where m.id = :id and m.deleted_at is null")
                .param("id", membershipId)
                .query(MembershipRepository::member)
                .optional();
    }

    /** A member of the current tenant by membership identifier, locked until the transaction ends. */
    Optional<Member> findForUpdate(UUID membershipId) {
        return jdbc.sql("select " + MEMBER_COLUMNS + " from membership m join platform_user u on u.id = m.user_id "
                        + "where m.id = :id and m.deleted_at is null for update of m")
                .param("id", membershipId)
                .query(MembershipRepository::member)
                .optional();
    }

    /** Changes the status. The database refuses an illegal move. */
    void setStatus(UUID membershipId, String status, ActorId actor) {
        jdbc.sql("update membership set status = :status, version = version + 1, updated_by = :actor "
                        + "where id = :id and deleted_at is null")
                .param("status", status)
                .param("actor", actor.value())
                .param("id", membershipId)
                .update();
    }

    /**
     * Takes the lock that serializes every change of who may do what in the current organization (the same one the
     * database guard of the last access manager takes, ADR-0044). Taken first, before any member row is locked, so the
     * order of locks is the same everywhere (access lock, member row, licence pool row).
     */
    void lockAccessChanges() {
        jdbc.sql("select pg_advisory_xact_lock(hashtextextended('access-managers:' "
                        + "|| platform_current_tenant()::text, 0))")
                .query().singleRow();
    }

    private static Own own(ResultSet rs, int row) throws SQLException {
        return new Own(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class), rs.getString("status"));
    }

    private static Member member(ResultSet rs, int row) throws SQLException {
        return new Member(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class), rs.getString("email"),
                rs.getString("display_name"), rs.getString("status"), rs.getBoolean("founding_administrator"),
                rs.getTimestamp("created_at").toInstant());
    }
}
