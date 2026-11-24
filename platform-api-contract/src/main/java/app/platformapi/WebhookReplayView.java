package app.platformapi;

/**
 * What a replay did (Sprint 32, ADR-0278): the deliveries that were put back in the queue as a new generation.
 *
 * @param replayed how many were put back; 0 when another click got there first
 * @param more whether more dead letters remain than one call replays
 */
public record WebhookReplayView(int replayed, boolean more) {
}
