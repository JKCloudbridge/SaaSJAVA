package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/**
 * An ability given to one member directly (Sprint 7), as the people who manage access see it.
 *
 * @param ability the ability key
 * @param reason the short note why, possibly empty
 * @param since when it was given
 */
public record GrantView(@NotNull String ability, @NotNull String reason, @NotNull Instant since) {

    @Override
    public String toString() {
        return "GrantView[redacted]";
    }
}
