package app.platformapi;

import jakarta.validation.constraints.NotNull;

/**
 * What the calls that make a signing secret answer: the subscription and the secret, shown this once and never again
 * (Sprint 32, ADR-0277). The platform keeps the secret sealed, because it must sign with it, and gives it to nobody; a
 * secret that was not copied is replaced, not read back. The value prints as redacted and is never logged or audited.
 *
 * @param detail the subscription after the change
 * @param secret the signing secret, for the receiver to verify deliveries with
 */
public record NewWebhookSecret(@NotNull WebhookView detail, @NotNull String secret) {

    @Override
    public String toString() {
        return "NewWebhookSecret[redacted]";
    }
}
