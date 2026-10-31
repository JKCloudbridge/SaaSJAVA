package app.platform.integration.internal;

import app.platform.tenant.TenantContexts;
import java.time.Duration;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The only class that names {@code integration_inbound_seen} (ADR-0221): the hash of the nonce of every accepted
 * signed call, kept for the replay window and no longer. The unique index decides, so two instances cannot both accept
 * the same call.
 */
@Repository
class ReplayStore {

    private final JdbcClient jdbc;
    private final TenantContexts contexts;

    ReplayStore(JdbcClient jdbc, TenantContexts contexts) {
        this.jdbc = jdbc;
        this.contexts = contexts;
    }

    /** Whether this is the first time the nonce was seen for the connection; false means a replay. */
    boolean firstSight(UUID connectionId, String nonceHash, UUID actor) {
        return jdbc.sql("insert into integration_inbound_seen (connection_id, nonce_hash, created_by, updated_by) "
                        + "values (:connection, :hash, :actor, :actor) "
                        + "on conflict (tenant_id, connection_id, nonce_hash) where deleted_at is null do nothing")
                .param("connection", connectionId).param("hash", nonceHash).param("actor", actor).update() == 1;
    }

    /** Removes what can no longer matter: older than twice the window, since an older call is refused by its time. */
    int purge(UUID connectionId, Duration window, int batch) {
        return jdbc.sql("delete from integration_inbound_seen where id in (select id from integration_inbound_seen "
                        + "where tenant_id = :tenant and connection_id = :connection "
                        + "and created_at < now() - make_interval(secs => :secs) limit :batch)")
                .param("tenant", contexts.require().tenantId().value()).param("connection", connectionId)
                .param("secs", (double) window.multipliedBy(2).toMillis() / 1000.0).param("batch", batch).update();
    }
}
