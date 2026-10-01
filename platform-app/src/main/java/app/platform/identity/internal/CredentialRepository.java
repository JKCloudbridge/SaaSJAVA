package app.platform.identity.internal;

import app.platform.sharedkernel.ActorId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The user_credential table: the password hash and the lock state. The hash leaves this class only into the sign-in
 * and password-change code; no query here is ever shown to a caller.
 */
@Repository
class CredentialRepository {

    /**
     * What sign-in needs to know about one user's credential.
     *
     * @param passwordHash the stored hash (secret-like: never logged, returned or audited)
     * @param state the recent failures and the lock
     */
    record Credential(String passwordHash, LockoutPolicy.State state) {

        @Override
        public String toString() {
            return "Credential[redacted]";
        }
    }

    private final JdbcClient jdbc;

    CredentialRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void insert(UUID userId, String passwordHash, ActorId actor) {
        jdbc.sql("insert into user_credential (user_id, password_hash, created_by, updated_by) "
                        + "values (:user, :hash, :actor, :actor)")
                .param("user", userId)
                .param("hash", passwordHash)
                .param("actor", actor.value())
                .update();
    }

    Optional<Credential> find(UUID userId) {
        return query(userId, "");
    }

    /** Reads and locks the row, so two concurrent failures of one account are counted one after the other. */
    Optional<Credential> findForUpdate(UUID userId) {
        return query(userId, " for update");
    }

    private Optional<Credential> query(UUID userId, String suffix) {
        return jdbc.sql("select password_hash, failed_attempts, last_failed_at, locked_until from user_credential "
                        + "where user_id = :user and deleted_at is null" + suffix)
                .param("user", userId)
                .query(CredentialRepository::map)
                .optional();
    }

    /** Stores a new password: resets the failures and the lock, and notes when the password changed. */
    void replacePassword(UUID userId, String passwordHash, ActorId actor) {
        jdbc.sql("update user_credential set password_hash = :hash, password_changed_at = now(), "
                        + "failed_attempts = 0, last_failed_at = null, locked_until = null, "
                        + "updated_by = :actor, version = version + 1 where user_id = :user and deleted_at is null")
                .param("hash", passwordHash)
                .param("actor", actor.value())
                .param("user", userId)
                .update();
    }

    /** Replaces the hash of the same password by a stronger one (upgrade at sign-in); nothing else changes. */
    void upgradeHash(UUID userId, String passwordHash) {
        jdbc.sql("update user_credential set password_hash = :hash, updated_by = :user, version = version + 1 "
                        + "where user_id = :user and deleted_at is null")
                .param("hash", passwordHash)
                .param("user", userId)
                .update();
    }

    void storeState(UUID userId, LockoutPolicy.State state) {
        jdbc.sql("update user_credential set failed_attempts = :attempts, last_failed_at = :last, "
                        + "locked_until = :until, updated_by = :user, version = version + 1 "
                        + "where user_id = :user and deleted_at is null")
                .param("attempts", state.failedAttempts())
                .param("last", state.lastFailedAt() == null ? null : java.sql.Timestamp.from(state.lastFailedAt()))
                .param("until", state.lockedUntil() == null ? null : java.sql.Timestamp.from(state.lockedUntil()))
                .param("user", userId)
                .update();
    }

    /** A successful sign-in: clears the failures and the lock and notes the time. */
    void recordSuccess(UUID userId) {
        jdbc.sql("update user_credential set failed_attempts = 0, last_failed_at = null, locked_until = null, "
                        + "last_sign_in_at = now(), updated_by = :user, version = version + 1 "
                        + "where user_id = :user and deleted_at is null")
                .param("user", userId)
                .update();
    }

    private static Credential map(ResultSet rs, int row) throws SQLException {
        return new Credential(rs.getString("password_hash"), new LockoutPolicy.State(
                rs.getInt("failed_attempts"),
                instant(rs.getObject("last_failed_at", OffsetDateTime.class)),
                instant(rs.getObject("locked_until", OffsetDateTime.class))));
    }

    private static Instant instant(OffsetDateTime time) {
        return time == null ? null : time.toInstant();
    }
}
