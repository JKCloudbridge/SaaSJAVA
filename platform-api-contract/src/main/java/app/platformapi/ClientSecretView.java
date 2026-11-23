package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

/**
 * One secret of a connected application, described and never shown (Sprint 31, ADR-0268): when it was made, when it
 * was last used, and whether and why it stops working. There is no field that could hold its value.
 *
 * @param id the identifier of the secret (used in the address that ends it)
 * @param number the number of the secret among those of the application, starting at one
 * @param state {@code ACTIVE}, {@code ENDING} (it works until {@code endsAt}) or {@code ENDED}
 * @param createdAt when it was made
 * @param lastUsedAt when it was last used (at most one minute behind), or null if never
 * @param endsAt when a secret that is ending stops working, or when an ended one stopped; null while active
 */
public record ClientSecretView(@NotNull UUID id, int number, @NotNull String state, @NotNull Instant createdAt,
        Instant lastUsedAt, Instant endsAt) {
}
