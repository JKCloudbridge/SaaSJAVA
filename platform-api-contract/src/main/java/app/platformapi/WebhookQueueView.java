package app.platformapi;

import java.time.Instant;

/**
 * What the delivery queue of one subscription holds, by state, as counts only (Sprint 32, ADR-0278). No identifier of a
 * record and no number of hidden records: a skipped delivery is counted, never named.
 *
 * @param pending deliveries waiting for their turn or for a retry
 * @param deadLettered deliveries set aside that an administrator may replay
 * @param done deliveries the receiver accepted
 * @param skipped deliveries that were not sent, for a stated reason
 * @param oldestOpen when the oldest waiting delivery is due, or null
 */
public record WebhookQueueView(long pending, long deadLettered, long done, long skipped, Instant oldestOpen) {
}
