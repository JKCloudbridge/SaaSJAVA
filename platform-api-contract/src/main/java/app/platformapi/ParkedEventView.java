package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

/**
 * An event that the platform's event relay gave up on after its handlers kept failing (Sprint 23, ADR-0143). The
 * payload is never shown.
 *
 * @param id the identifier of the event
 * @param type what happened, for example {@code metadata.published}
 * @param occurredAt when it was recorded
 * @param attempts how often delivery was tried
 * @param lastFailureType the type name of the last failure, never its message
 */
public record ParkedEventView(@NotNull UUID id, @NotNull String type, @NotNull Instant occurredAt,
        @NotNull Integer attempts, String lastFailureType) {
}
