package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

/**
 * One send to, or accepted change from, an external system (Sprint 28, ADR-0220). Facts only: never a record value or
 * a payload.
 *
 * @param id the identifier
 * @param direction {@code OUT} or {@code IN}
 * @param kind {@code CREATE}, {@code UPDATE}, {@code DELETE} or {@code UPSERT}
 * @param object the API name of the object
 * @param recordId the record it was about, or null
 * @param state {@code PENDING}, {@code DONE}, {@code FAILED} or {@code SKIPPED}
 * @param reason a stable key, or null
 * @param words the platform's own sentence for the reason, or null
 * @param httpStatus the status the external system answered with, or null
 * @param createdAt when it began
 * @param finishedAt when it ended, or null while pending
 * @param stale whether it is still pending long after it should have ended (the instance that began it stopped)
 */
public record DeliveryView(@NotNull UUID id, @NotNull String direction, @NotNull String kind, @NotNull String object,
        UUID recordId, @NotNull String state, String reason, String words, Integer httpStatus,
        @NotNull Instant createdAt, Instant finishedAt, @NotNull Boolean stale) {
}
