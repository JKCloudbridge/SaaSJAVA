package app.platform.identity.internal;

import app.platform.sharedkernel.ActorId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The short-lived sign-in sessions (table login_session, ADR-0019). The browser holds a random secret; only its hash is
 * stored. A session is alive while it is not revoked, not expired, its user may sign in and the user's security version
 * is still the one it was issued under: the same rule as for tokens, decided in one query so there is no stale
 * in-memory copy on any instance.
 */
@Repository
class LoginSessions {

    /**
     * An alive session.
     *
     * @param userId the user
     * @param boundTenantId the organization host it was issued on, null for the platform host
     */
    record Alive(UUID sessionId, UUID userId, UUID boundTenantId) {
    }

    private final JdbcClient jdbc;

    LoginSessions(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Creates a session and returns its secret (shown once, to the browser). */
    String create(UUID userId, UUID boundTenantId, long securityVersion, Duration lifetime) {
        String secret = Hashes.randomSecret();
        jdbc.sql("insert into login_session (token_hash, user_id, bound_tenant_id, security_version, expires_at, "
                        + "created_by, updated_by) values (:hash, :user, :tenant, :version, "
                        + "now() + make_interval(secs => :seconds), :user, :user)")
                .param("hash", Hashes.stored(secret))
                .param("user", userId)
                .param("tenant", boundTenantId)
                .param("version", securityVersion)
                .param("seconds", (double) lifetime.toSeconds())
                .update();
        return secret;
    }

    /** The session behind a cookie value, if it is alive. */
    Optional<Alive> findAlive(String secret) {
        return jdbc.sql("select s.id, s.user_id, s.bound_tenant_id from login_session s "
                        + "join platform_user u on u.id = s.user_id "
                        + "where s.token_hash = :hash and s.deleted_at is null and s.revoked_at is null "
                        + "and s.expires_at > now() and u.status = 'ACTIVE' and u.deleted_at is null "
                        + "and u.security_version = s.security_version")
                .param("hash", Hashes.stored(secret))
                .query(LoginSessions::map)
                .optional();
    }

    /** Ends the session behind a cookie value. */
    void revoke(String secret) {
        jdbc.sql("update login_session set revoked_at = now(), updated_by = user_id, version = version + 1 "
                        + "where token_hash = :hash and revoked_at is null and deleted_at is null")
                .param("hash", Hashes.stored(secret))
                .update();
    }

    /** Ends every session of a user. @return how many were alive */
    int revokeAll(UUID userId, ActorId actor) {
        return jdbc.sql("update login_session set revoked_at = now(), updated_by = :actor, version = version + 1 "
                        + "where user_id = :user and revoked_at is null and deleted_at is null")
                .param("actor", actor.value())
                .param("user", userId)
                .update();
    }

    /** Ends every session of a user that was issued on one organization's host. @return how many were alive */
    int revokeAllIn(UUID userId, UUID boundTenantId, ActorId actor) {
        return jdbc.sql("update login_session set revoked_at = now(), updated_by = :actor, version = version + 1 "
                        + "where user_id = :user and bound_tenant_id = :tenant and revoked_at is null "
                        + "and deleted_at is null")
                .param("actor", actor.value())
                .param("user", userId)
                .param("tenant", boundTenantId)
                .update();
    }

    /** Removes sessions that ended long ago. @return how many */
    int purgeEndedBefore(Duration age) {
        return jdbc.sql("delete from login_session where expires_at < now() - make_interval(secs => :seconds)")
                .param("seconds", (double) age.toSeconds())
                .update();
    }

    private static Alive map(ResultSet rs, int row) throws SQLException {
        return new Alive(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                rs.getObject("bound_tenant_id", UUID.class));
    }
}
