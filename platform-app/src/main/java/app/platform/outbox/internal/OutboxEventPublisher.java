package app.platform.outbox.internal;

import app.platform.sharedkernel.ActorId;
import app.platform.sharedkernel.events.EventPublisher;
import app.platform.sharedkernel.events.NewEvent;
import app.platform.tenant.TenantContext;
import app.platform.tenant.TenantContexts;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Writes an event into the outbox table, in the caller's transaction and for the caller's tenant (ADR-0016).
 *
 * <p>Both preconditions are checked because breaking either is a bug that would otherwise stay silent: an event
 * written outside a transaction could outlive a change that was rolled back, and an event without a tenant context
 * has no owner. The tenant is taken from the context, never from an argument; row level security refuses the insert if
 * the database session disagrees.
 */
@Component
class OutboxEventPublisher implements EventPublisher {

    private final JdbcClient jdbc;
    private final TenantContexts contexts;

    OutboxEventPublisher(JdbcClient jdbc, TenantContexts contexts) {
        this.jdbc = jdbc;
        this.contexts = contexts;
    }

    @Override
    public void publish(NewEvent event) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("An event must be published inside the transaction of its change");
        }
        TenantContext context = contexts.require();
        UUID actor = context.user().orElse(ActorId.SYSTEM.value());
        jdbc.sql("insert into outbox_event (tenant_id, user_id, membership_id, event_type, payload, created_by, "
                        + "updated_by) values (:tenant, :user, :membership, :type, cast(:payload as jsonb), :actor, "
                        + ":actor)")
                .param("tenant", context.tenantId().value())
                .param("user", context.userId())
                .param("membership", context.membershipId())
                .param("type", event.type())
                .param("payload", event.payload())
                .param("actor", actor)
                .update();
    }
}
