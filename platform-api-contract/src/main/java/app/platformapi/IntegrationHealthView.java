package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/**
 * How the flows of one connection stand (Sprint 29, ADR-0235): the circuit, the queue, the limits and the last signs
 * of life. The same facts as the metrics, counts and times only.
 *
 * @param name the connection's name
 * @param label what a person calls it
 * @param connectionState the state of the connection itself (a connection that is off holds its queue)
 * @param circuit the circuit breaker
 * @param limits the limits on sending
 * @param waiting deliveries waiting to be sent, or to be sent again
 * @param sending deliveries being sent now
 * @param waitingRemote deliveries accepted by the other side and waiting for it to finish
 * @param deadLettered deliveries set aside that an administrator may send again
 * @param done deliveries that were sent (within the retention)
 * @param oldestOpenSince when the oldest open delivery became due, or null when none is open
 * @param lastDoneAt when a delivery last succeeded, or null
 * @param lastDeadLetterAt when a delivery was last set aside, or null
 * @param files how the files of the connection stand, for a connection that picks up files; null for every other
 *        (Sprint 29A)
 */
public record IntegrationHealthView(@NotNull String name, @NotNull String label, @NotNull String connectionState,
        @NotNull CircuitView circuit, @NotNull FlowLimitsView limits, @NotNull Long waiting, @NotNull Long sending,
        @NotNull Long waitingRemote, @NotNull Long deadLettered, @NotNull Long done, Instant oldestOpenSince,
        Instant lastDoneAt, Instant lastDeadLetterAt, FileFlowView files) {
}
