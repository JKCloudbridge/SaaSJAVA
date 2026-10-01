package app.platform.sharedkernel.events;

/**
 * Announces events through the transactional outbox (decision D3, ADR-0016).
 *
 * <p>The event is written to the database in the <strong>caller's own transaction</strong>, so it exists if and only
 * if the change it describes was committed: a rollback discards both, and a crash after the commit cannot lose it.
 * Handlers run later, after the commit, in another transaction.
 *
 * <p>The implementation refuses to publish outside a transaction and outside a tenant context, because both are
 * programming mistakes: without a transaction the event could outlive a rolled-back change, and every event
 * belongs to exactly one tenant, which is taken from the tenant context, never from the caller.
 */
public interface EventPublisher {

    /**
     * Records an event for the current tenant.
     *
     * @param event what happened
     * @throws IllegalStateException when no transaction or no tenant context is active
     */
    void publish(NewEvent event);
}
