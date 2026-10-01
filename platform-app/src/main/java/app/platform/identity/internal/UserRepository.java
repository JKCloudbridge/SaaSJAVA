package app.platform.identity.internal;

import app.platform.identity.User;
import app.platform.identity.UserStatus;
import app.platform.sharedkernel.ActorId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The user table. Platform-level (ADR-0022): read and written without a tenant context. */
@Repository
class UserRepository {

    private static final String COLUMNS =
            "id, email, display_name, status, security_version, email_verified_at, version";

    private final JdbcClient jdbc;

    UserRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Inserts a user in the INVITED state. A duplicate address raises {@code DuplicateKeyException}. */
    UUID insert(String email, String displayName, ActorId actor) {
        return jdbc.sql("insert into platform_user (email, display_name, created_by, updated_by) "
                        + "values (:email, :name, :actor, :actor) returning id")
                .param("email", email)
                .param("name", displayName)
                .param("actor", actor.value())
                .query(UUID.class)
                .single();
    }

    Optional<User> findById(UUID id) {
        return jdbc.sql("select " + COLUMNS + " from platform_user where id = :id and deleted_at is null")
                .param("id", id)
                .query(UserRepository::map)
                .optional();
    }

    Optional<User> findByEmail(String normalizedEmail) {
        return jdbc.sql("select " + COLUMNS + " from platform_user where email = :email and deleted_at is null")
                .param("email", normalizedEmail)
                .query(UserRepository::map)
                .optional();
    }

    /** Reads and locks the row, so two concurrent changes of one user are decided one after the other. */
    Optional<User> findForUpdate(UUID id) {
        return jdbc.sql("select " + COLUMNS + " from platform_user where id = :id and deleted_at is null for update")
                .param("id", id)
                .query(UserRepository::map)
                .optional();
    }

    /**
     * Moves the user to a new status. Leaving ACTIVE also raises the security version (the database trigger does it
     * as well, so a hand-written statement cannot forget).
     *
     * @return whether a row was changed; false means the version was stale
     */
    boolean updateStatus(User current, UserStatus target, ActorId actor) {
        String verified = target == UserStatus.ACTIVE && current.emailVerifiedAt() == null
                ? ", email_verified_at = now()" : "";
        return jdbc.sql("update platform_user set status = :status, updated_by = :actor, version = version + 1"
                        + verified + " where id = :id and version = :version")
                .param("status", target.name())
                .param("actor", actor.value())
                .param("id", current.id())
                .param("version", current.version())
                .update() == 1;
    }

    /** Raises the security version by one: every outstanding token and login session of the user stops working. */
    void bumpSecurityVersion(UUID id, ActorId actor) {
        jdbc.sql("update platform_user set security_version = security_version + 1, updated_by = :actor, "
                        + "version = version + 1 where id = :id and deleted_at is null")
                .param("actor", actor.value())
                .param("id", id)
                .update();
    }

    private static User map(ResultSet rs, int row) throws SQLException {
        OffsetDateTime verified = rs.getObject("email_verified_at", OffsetDateTime.class);
        return new User(
                rs.getObject("id", UUID.class),
                rs.getString("email"),
                rs.getString("display_name"),
                UserStatus.valueOf(rs.getString("status")),
                rs.getLong("security_version"),
                verified == null ? null : verified.toInstant(),
                rs.getLong("version"));
    }
}
