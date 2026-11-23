package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

/**
 * One delivery of a webhook subscription: identifiers, a state and a stable reason, never a payload body, a header or an
 * answer (Sprint 32, ADR-0278).
 *
 * @param id the delivery identifier, which is also sent to the receiver as the key to deduplicate on
 * @param eventType the event that caused it
 * @param object the API name of the object of the record
 * @param recordId the record the event is about, or null when the member the webhook acts as could not read it (a
 *        skipped delivery never names the record, so the log cannot be used to find out that a record exists)
 * @param recordVersion the version of the record at the event, when known
 * @param state {@code PENDING}, {@code SENDING}, {@code DONE}, {@code SKIPPED}, {@code SUPERSEDED} or
 *        {@code DEAD_LETTERED}
 * @param reason the stable key of why it ended as it did, or null
 * @param reasonWords the platform's own sentence for that reason, or null
 * @param queuedReason the stable key of why it is waiting, or null
 * @param queuedReasonWords the platform's own sentence for that, or null
 * @param generation 1, and one more for every replay
 * @param attempts how many attempts this generation made
 * @param createdAt when the event was turned into a delivery
 * @param finishedAt when it ended, or null while it is open
 * @param nextAttemptAt when it is due next, or null once it has ended
 * @param lastStatusClass the class of the last answer, or null
 */
public record WebhookDeliveryView(@NotNull UUID id, @NotNull String eventType, @NotNull String object,
        UUID recordId, Long recordVersion, @NotNull String state, String reason, String reasonWords,
        String queuedReason, String queuedReasonWords, int generation, int attempts, @NotNull Instant createdAt,
        Instant finishedAt, Instant nextAttemptAt, String lastStatusClass) {
}
