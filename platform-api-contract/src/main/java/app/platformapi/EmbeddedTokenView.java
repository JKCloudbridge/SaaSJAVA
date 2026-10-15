package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/**
 * An access token for a custom page's script (Sprint 18, ADR-0127). It is shown once, kept in the memory of the page
 * and never stored. It works as a bearer token for the data interface only and ends in a few minutes.
 *
 * @param token the token
 * @param expiresAt when it ends
 */
public record EmbeddedTokenView(@NotNull String token, @NotNull Instant expiresAt) {

    /** The token is a secret. */
    @Override
    public String toString() {
        return "EmbeddedTokenView[redacted]";
    }
}
