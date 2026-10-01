package app.platform.sharedkernel.events;

import java.util.Set;

/**
 * Reacts to events from the outbox. A module registers a handler by exposing one as a Spring bean; the outbox
 * relay finds it, so the module needs no dependency on the outbox itself.
 *
 * <h2>The contract</h2>
 * <ul>
 *   <li><strong>At least once, made effectively once.</strong> The relay may deliver an event more than once (a crash
 *       or a stalled worker). It records, in the same transaction as the handler's own changes, that this consumer
 *       handled this event; a second delivery is skipped. So a handler's database changes happen once.</li>
 *   <li><strong>Only database changes are protected.</strong> A handler that calls something outside the database
 *       (sends mail, calls an external system) must pass {@link EventEnvelope#eventId()} as the idempotency key
 *       of that call, because the call may be repeated when the handler's transaction fails afterwards.</li>
 *   <li><strong>Tenant context.</strong> The event's tenant is active while the handler runs; the database only shows
 *       that tenant's rows. A handler never needs, and must not take, a tenant from the payload.</li>
 *   <li><strong>Failure.</strong> An exception rolls back the handler's changes and the event is retried with a
 *       growing delay, then set aside as a dead letter. Throw for failures worth retrying; handle permanent
 *       business outcomes by recording them.</li>
 *   <li><strong>Order.</strong> Events are claimed roughly in recording order, but not guaranteed to be handled in
 *       order, especially across retries. A handler must tolerate that.</li>
 * </ul>
 */
public interface EventHandler {

    /**
     * The stable name of this consumer. It is half of the idempotency key, so it must never change and must be
     * unique among handlers, for example {@code audit.tenant-events}.
     */
    String consumerName();

    /** The event types this handler wants, for example {@code Set.of("tenant.suspended")}. */
    Set<String> eventTypes();

    /**
     * Handles one event inside a transaction with the event's tenant context.
     *
     * @param event the event
     */
    void handle(EventEnvelope event);
}
