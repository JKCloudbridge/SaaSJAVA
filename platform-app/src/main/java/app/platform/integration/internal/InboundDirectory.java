package app.platform.integration.internal;

import app.platform.sharedkernel.TenantId;
import app.platform.tenant.SystemScope;
import app.platform.tenant.TenantContexts;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Finds the connection a public address names, and through it the organization (Sprint 28, ADR-0221). The one class,
 * besides the receiver that asks it, that enters the system scope {@code inbound_lookup}; it reads one row by the
 * unguessable identity in the address and nothing else, and the organization of an inbound call is never taken from
 * the host, a header or the body.
 *
 * <p>An unknown identity and a connection that was removed answer alike (empty), and so, as the caller sees it, does
 * a connection that is not switched on: the receiver cannot be used to learn which connections exist.
 */
@Component
class InboundDirectory {

    /**
     * Where a public address leads.
     *
     * @param tenant the organization the connection belongs to
     * @param connection the connection
     * @param on whether the connection is switched on
     */
    record Target(TenantId tenant, UUID connection, boolean on) {
    }

    private final JdbcClient jdbc;
    private final TenantContexts contexts;
    private final TransactionTemplate transaction;

    InboundDirectory(JdbcClient jdbc, TenantContexts contexts, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.contexts = contexts;
        this.transaction = new TransactionTemplate(transactions);
    }

    Optional<Target> find(String inboundId) {
        Supplier<Optional<Target>> lookup = () -> transaction.execute(status -> jdbc.sql(
                        "select tenant_id, id, state from integration_connection where inbound_id = :id "
                                + "and deleted_at is null")
                .param("id", inboundId).query((rs, row) -> new Target(
                        new TenantId(rs.getObject("tenant_id", UUID.class)), rs.getObject("id", UUID.class),
                        "ENABLED".equals(rs.getString("state")))).optional());
        // A request on an organization host has a tenant; the lookup sets it aside. On the platform host it has none.
        return contexts.current().isPresent() ? contexts.callAsSystemApart(SystemScope.INBOUND_LOOKUP, lookup)
                : contexts.callAsSystem(SystemScope.INBOUND_LOOKUP, lookup);
    }
}
