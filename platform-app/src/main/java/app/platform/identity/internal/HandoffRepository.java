package app.platform.identity.internal;

import app.platform.sharedkernel.ActorId;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The organization_handoff table (ADR-0029): a signed-in person's one-time request to continue on another
 * organization's host. Only hashes are stored; the lookup is by the hash of what the person presents.
 */
@Repository
class HandoffRepository {

    /** A live handoff: not used, not expired. */
    record Live(UUID id, UUID userId, UUID boundTenantId) {
    }

    private final JdbcClient jdbc;

    HandoffRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void insert(UUID userId, UUID boundTenantId, String storedHash, Instant expiresAt) {
        jdbc.sql("insert into organization_handoff (token_hash, user_id, bound_tenant_id, expires_at, created_by, "
                        + "updated_by) values (:hash, :user, :tenant, :expires, :user, :user)")
                .param("hash", storedHash)
                .param("user", userId)
                .param("tenant", boundTenantId)
                .param("expires", Timestamp.from(expiresAt))
                .update();
    }

    Optional<Live> findLive(String storedHash, Instant now) {
        return jdbc.sql("select id, user_id, bound_tenant_id from organization_handoff where token_hash = :hash "
                        + "and used_at is null and expires_at > :now and deleted_at is null")
                .param("hash", storedHash)
                .param("now", Timestamp.from(now))
                .query((rs, row) -> new Live(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                        rs.getObject("bound_tenant_id", UUID.class)))
                .optional();
    }

    /** Uses the handoff up. Succeeds for exactly one caller. */
    boolean consume(UUID id, Instant now) {
        return jdbc.sql("update organization_handoff set used_at = :now, updated_by = :actor, version = version + 1 "
                        + "where id = :id and used_at is null and expires_at > :now and deleted_at is null")
                .param("now", Timestamp.from(now))
                .param("actor", ActorId.SYSTEM.value())
                .param("id", id)
                .update() == 1;
    }

    int purgeExpiredBefore(Instant cutoff) {
        return jdbc.sql("delete from organization_handoff where expires_at < :cutoff")
                .param("cutoff", Timestamp.from(cutoff))
                .update();
    }
}
