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
 * default and the policy take it from the transaction. The database also enforces the lifecycle (legal status moves,
 * the administrator marker, the last administrator), so a bug here cannot break those rules.
 */
@Repository
class MembershipRepository {

    /** The statuses a membership can have. */
    static final String ACTIVE = "ACTIVE";
    static final String DEACTIVATED = "DEACTIVATED";

    /**
     * A member as the administrators see them.
     *
     * @param founding the historical fact "this person created the organization"; grants nothing
     */
    record Member(UUID id, UUID userId, String email, String displayName, String status, boolean administrator,
            boolean founding, Instant since) {
    }

    /** The membership of one user in the current organization. */
    record Own(UUID id, UUID userId, String status, boolean administrator) {

        boolean active() {
            return ACTIVE.equals(status);
        }
    }

    private static final String MEMBER_COLUMNS = "m.id, m.user_id, u.email, u.display_name, m.status, "
            + "m.administrator, m.founding_administrator, m.created_at";

    private final JdbcClient jdbc;

    MembershipRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Makes the user a member of the current tenant and marks them as the organization's founding administrator (a
     * historical fact) and as an administrator (the working marker).
     */
    UUID insertFounder(UUID userId, ActorId actor) {
        return insert(userId, true, true, actor);
    }

    /** Makes the user a member of the current tenant. */
    UUID insertMember(UUID userId, boolean administrator, boolean founding, ActorId actor) {
        return insert(userId, administrator, founding, actor);
    }

    private UUID insert(UUID userId, boolean administrator, boolean founding, ActorId actor) {
        return jdbc.sql("insert into membership (user_id, administrator, founding_administrator, created_by, "
                        + "updated_by) values (:user, :administrator, :founding, :actor, :actor) returning id")
                .param("user", userId)
                .param("administrator", administrator)
                .param("founding", founding)
                .param("actor", actor.value())
                .query(UUID.class)
                .single();
    }

    /** The user's membership of the current tenant, in any status. */
    Optional<Own> findOwn(UUID userId) {
        return jdbc.sql("select id, user_id, status, administrator from membership "
                        + "where user_id = :user and deleted_at is null")
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

    /** A member of the current tenant by membership identifier, locked until the transaction ends. */
    Optional<Member> findForUpdate(UUID membershipId) {
        return jdbc.sql("select " + MEMBER_COLUMNS + " from membership m join platform_user u on u.id = m.user_id "
                        + "where m.id = :id and m.deleted_at is null for update of m")
                .param("id", membershipId)
                .query(MembershipRepository::member)
                .optional();
    }

    /** Changes the status. The database refuses an illegal move and the removal of the last administrator. */
    void setStatus(UUID membershipId, String status, ActorId actor) {
        jdbc.sql("update membership set status = :status, version = version + 1, updated_by = :actor "
                        + "where id = :id and deleted_at is null")
                .param("status", status)
                .param("actor", actor.value())
                .param("id", membershipId)
                .update();
    }

    /** Sets or clears the administrator marker. The database refuses to clear the last one. */
    void setAdministrator(UUID membershipId, boolean administrator, ActorId actor) {
        jdbc.sql("update membership set administrator = :value, version = version + 1, updated_by = :actor "
                        + "where id = :id and deleted_at is null")
                .param("value", administrator)
                .param("actor", actor.value())
                .param("id", membershipId)
                .update();
    }

    /**
     * Takes the lock that serializes changes to the administrators of the current tenant (the same one the database
     * guard takes), then counts the active administrators. Lets the service give a clear answer before the database
     * would refuse.
     */
    long countActiveAdministratorsLocked() {
        jdbc.sql("select pg_advisory_xact_lock(hashtextextended('membership-administrators:' "
                        + "|| platform_current_tenant()::text, 0))")
                .query().singleRow();
        return jdbc.sql("select count(*) from membership where administrator and status = 'ACTIVE' "
                        + "and deleted_at is null")
                .query(Long.class)
                .single();
    }

    private static Own own(ResultSet rs, int row) throws SQLException {
        return new Own(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class), rs.getString("status"),
                rs.getBoolean("administrator"));
    }

    private static Member member(ResultSet rs, int row) throws SQLException {
        return new Member(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class), rs.getString("email"),
                rs.getString("display_name"), rs.getString("status"), rs.getBoolean("administrator"),
                rs.getBoolean("founding_administrator"), rs.getTimestamp("created_at").toInstant());
    }
}
