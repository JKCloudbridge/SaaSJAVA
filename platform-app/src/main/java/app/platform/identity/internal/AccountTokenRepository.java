package app.platform.identity.internal;

import app.platform.identity.AccountTokenPurpose;
import app.platform.sharedkernel.ActorId;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The account_token table (ADR-0023). Only hashes are stored; the lookup is by the hash of what the person presents.
 * Times are passed in, not read from the database clock, so a test with a controlled clock sees one consistent time.
 */
@Repository
class AccountTokenRepository {

    /**
     * A live token: not used, not cancelled, not expired. An invitation token also names the organization and the
     * invitation it resolves to (both null for the other purposes).
     */
    record Live(UUID id, AccountTokenPurpose purpose, String email, UUID userId, UUID contextTenantId,
            UUID invitationId) {
    }

    private final JdbcClient jdbc;

    AccountTokenRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void insert(AccountTokenPurpose purpose, String email, UUID userId, String storedHash, Instant expiresAt) {
        jdbc.sql("insert into account_token (purpose, email, user_id, token_hash, expires_at, created_by, updated_by) "
                        + "values (:purpose, :email, :user, :hash, :expires, :actor, :actor)")
                .param("purpose", purpose.name())
                .param("email", email)
                .param("user", userId)
                .param("hash", storedHash)
                .param("expires", Timestamp.from(expiresAt))
                .param("actor", ActorId.SYSTEM.value())
                .update();
    }

    void insertInvitation(String email, UUID tenantId, UUID invitationId, String storedHash, Instant expiresAt) {
        jdbc.sql("insert into account_token (purpose, email, token_hash, expires_at, context_tenant_id, "
                        + "invitation_id, created_by, updated_by) values ('INVITATION', :email, :hash, :expires, "
                        + ":tenant, :invitation, :actor, :actor)")
                .param("email", email)
                .param("hash", storedHash)
                .param("expires", Timestamp.from(expiresAt))
                .param("tenant", tenantId)
                .param("invitation", invitationId)
                .param("actor", ActorId.SYSTEM.value())
                .update();
    }

    /** Cancels the unused links of one invitation (sent again, accepted or revoked); returns how many. */
    int cancelOpenOfInvitation(UUID invitationId) {
        return jdbc.sql("update account_token set revoked_at = now(), updated_by = :actor, version = version + 1 "
                        + "where invitation_id = :invitation and used_at is null and revoked_at is null "
                        + "and deleted_at is null")
                .param("actor", ActorId.SYSTEM.value())
                .param("invitation", invitationId)
                .update();
    }

    /** Cancels the unused tokens of one purpose for one address; returns how many. */
    int cancelOpen(AccountTokenPurpose purpose, String email) {
        return jdbc.sql("update account_token set revoked_at = now(), updated_by = :actor, version = version + 1 "
                        + "where purpose = :purpose and email = :email and used_at is null and revoked_at is null "
                        + "and deleted_at is null")
                .param("actor", ActorId.SYSTEM.value())
                .param("purpose", purpose.name())
                .param("email", email)
                .update();
    }

    /** Cancels every unused reset token of a user (the password changed, so older links must not work). */
    int cancelOpenResetsOf(UUID userId) {
        return jdbc.sql("update account_token set revoked_at = now(), updated_by = :actor, version = version + 1 "
                        + "where user_id = :user and purpose = 'PASSWORD_RESET' and used_at is null "
                        + "and revoked_at is null and deleted_at is null")
                .param("actor", ActorId.SYSTEM.value())
                .param("user", userId)
                .update();
    }

    /** The live token with this stored hash and purpose, if there is one. */
    Optional<Live> findLive(String storedHash, AccountTokenPurpose purpose, Instant now) {
        return jdbc.sql("select id, purpose, email, user_id, context_tenant_id, invitation_id from account_token "
                        + "where token_hash = :hash "
                        + "and purpose = :purpose and used_at is null and revoked_at is null "
                        + "and expires_at > :now and deleted_at is null")
                .param("hash", storedHash)
                .param("purpose", purpose.name())
                .param("now", Timestamp.from(now))
                .query((rs, row) -> new Live(rs.getObject("id", UUID.class),
                        AccountTokenPurpose.valueOf(rs.getString("purpose")), rs.getString("email"),
                        rs.getObject("user_id", UUID.class), rs.getObject("context_tenant_id", UUID.class),
                        rs.getObject("invitation_id", UUID.class)))
                .optional();
    }

    /**
     * Uses the token up. Succeeds for exactly one caller: the conditional update waits for a concurrent one on the
     * same row and then finds it used.
     */
    boolean consume(UUID id, Instant now) {
        return jdbc.sql("update account_token set used_at = :now, updated_by = :actor, version = version + 1 "
                        + "where id = :id and used_at is null and revoked_at is null and expires_at > :now "
                        + "and deleted_at is null")
                .param("now", Timestamp.from(now))
                .param("actor", ActorId.SYSTEM.value())
                .param("id", id)
                .update() == 1;
    }

    /** Removes tokens that expired before the given time (used and cancelled ones expire like the rest). */
    int purgeExpiredBefore(Instant cutoff) {
        return jdbc.sql("delete from account_token where expires_at < :cutoff")
                .param("cutoff", Timestamp.from(cutoff))
                .update();
    }
}
