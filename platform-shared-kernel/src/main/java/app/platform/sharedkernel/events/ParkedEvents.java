package app.platform.sharedkernel.events;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The events of the current organization that the relay gave up on (ADR-0016, ADR-0143): their handlers kept failing
 * and the event was parked as a dead letter. A module that depends on its handlers seeing every event (workflows) lists
 * the ones that concern it and puts one back, without depending on the outbox. Implemented by the outbox.
 *
 * <p>Everything here runs in the organization's own tenant context, under its row level security: it can neither see
 * nor put back another organization's event. The payload is never shown (an event carries identifiers, but a listing
 * is not the place for them). Putting an event back is safe because consumers are idempotent: one that handled the
 * event already finds its marker and skips it.
 */
public interface ParkedEvents {

    /**
     * A parked event.
     *
     * @param id the event
     * @param type what happened
     * @param occurredAt when it was recorded
     * @param attempts how often delivery was tried
     * @param lastFailureType the type name of the last failure, never its message
     */
    record Parked(UUID id, String type, Instant occurredAt, int attempts, String lastFailureType) {
    }

    /** The parked events of these types, newest first, at most {@code limit}. */
    List<Parked> list(Set<String> eventTypes, int limit);

    /** How many events of these types are parked. */
    long count(Set<String> eventTypes);

    /**
     * Puts a parked event of one of these types back to be delivered again, at once, as if it were new.
     *
     * @return true when the event was parked and is now queued; false when there is no such parked event (it was
     *         redriven already, or it is not of one of these types)
     */
    boolean redrive(UUID eventId, Set<String> eventTypes);
}
