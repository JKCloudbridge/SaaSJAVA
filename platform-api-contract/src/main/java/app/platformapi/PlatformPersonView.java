package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

/**
 * A platform role held by a person, as the platform administrators see it.
 *
 * @param id the role assignment (what is revoked)
 * @param email the address of the person
 * @param displayName the name of the person
 * @param role {@code PLATFORM_ADMIN}, {@code PLATFORM_SUPPORT} or {@code PLATFORM_BILLING}
 * @param since when the role was granted
 */
public record PlatformPersonView(@NotNull UUID id, @NotNull String email, @NotNull String displayName,
        @NotNull String role, @NotNull Instant since) {

    @Override
    public String toString() {
        return "PlatformPersonView[redacted]";
    }
}
