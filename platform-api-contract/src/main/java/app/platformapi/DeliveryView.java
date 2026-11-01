package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

/**
 * One send to, or accepted change from, an external system (Sprint 28, ADR-0220; the queue since Sprint 29,
 * ADR-0230). Facts only: never a record value, a payload, a header, an address or an identifier of the other system.
 *
 * @param id the identifier
 * @param direction {@code OUT} or {@code IN}
 * @param kind {@code CREATE}, {@code UPDATE}, {@code DELETE} or {@code UPSERT}
 * @param object the API name of the object
 * @param recordId the record it was about, or null
 * @param state where it stands: waiting, being sent, waiting for the other side, done, skipped, replaced, set aside
 *        as a dead letter or failed
 * @param reason a stable key, or null: why it ended, or why the last attempt failed while it waits
 * @param words the platform's own sentence for the reason, or null
 * @param httpStatus the status the external system answered with, or null
 * @param createdAt when it began
 * @param finishedAt when it ended, or null while it is open
 * @param stale whether it is open and overdue by far (nothing has worked on it for a long time)
 * @param attempts how many attempts were made in the current generation
 * @param generation 1, and one more each time an administrator sent it again
 * @param nextAttemptAt when it will be looked at next, or null once it has ended
 * @param waitingFor the stable key of what it waits for (a pause, a limit, a wait after a failure), or null
 * @param waitingWords the platform's own sentence for it, or null
 * @param statusClass the class of the last answer ({@code 2xx} to {@code 5xx}, or {@code none}), or null
 * @param canReplay whether it is a dead letter that an administrator may send again
 */
public record DeliveryView(@NotNull UUID id, @NotNull String direction, @NotNull String kind, @NotNull String object,
        UUID recordId, @NotNull String state, String reason, String words, Integer httpStatus,
        @NotNull Instant createdAt, Instant finishedAt, @NotNull Boolean stale, @NotNull Integer attempts,
        @NotNull Integer generation, Instant nextAttemptAt, String waitingFor, String waitingWords,
        String statusClass, @NotNull Boolean canReplay) {
}
