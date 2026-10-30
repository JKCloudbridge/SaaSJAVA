package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/**
 * A credential of the organization as an administrator sees it (Sprint 27, ADR-0213). It says that a credential exists
 * and when it changed; there is no field that could hold its value, and no answer of the platform ever carries one.
 *
 * @param name the permanent name; connections refer to a credential by it
 * @param version the number of the current version; every write makes the next one
 * @param createdAt when the first version was written
 * @param updatedAt when the current version was written
 * @param lastUsedAt the clock hour of the latest use, or null when it was never used
 * @param usedBy how many connections name this credential
 * @param canRollBack whether an earlier version is kept to go back to
 */
public record CredentialView(@NotNull String name, int version, @NotNull Instant createdAt,
        @NotNull Instant updatedAt, Instant lastUsedAt, int usedBy, boolean canRollBack) {
}
