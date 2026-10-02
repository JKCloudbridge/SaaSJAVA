package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/**
 * One live sign-in of a person. It shows no token, hash or secret.
 *
 * @param kind {@code SIGN_IN} (the short step that starts a sign-in) or {@code TOKENS} (a signed-in browser or program)
 * @param organization the short name of the organization host it works on, absent for the platform host
 * @param started when it began
 * @param expires when it ends at the latest
 */
public record SessionInfo(@NotNull String kind, String organization, @NotNull Instant started,
        @NotNull Instant expires) {
}
