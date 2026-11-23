package app.platformapi;

/**
 * Switches a verified webhook subscription on (Sprint 32, ADR-0276).
 *
 * @param version the version read
 */
public record EnableWebhookRequest(long version) {
}
