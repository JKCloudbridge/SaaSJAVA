package app.platformapi;

import jakarta.validation.constraints.NotNull;

/**
 * What the one call that makes a secret answers: the application and the secret, shown this once and never again
 * (Sprint 31, ADR-0268). The platform keeps only a hash, so a secret that was not copied is lost: the cure is a new
 * secret. The value prints as redacted and is never logged or audited.
 *
 * @param detail the application after the change
 * @param clientSecret the secret, to be sent with the client identifier when asking for a token
 */
public record NewClientSecret(@NotNull ConnectedAppDetail detail, @NotNull String clientSecret) {

    @Override
    public String toString() {
        return "NewClientSecret[redacted]";
    }
}
