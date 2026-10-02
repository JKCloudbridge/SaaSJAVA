package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

/**
 * The state of the invitation of the first administrator of an organization. It never shows the address.
 *
 * @param id the invitation
 * @param status {@code OPEN}, {@code EXPIRED}, {@code ACCEPTED} or {@code REVOKED}
 * @param expiresAt when the link stops working
 * @param sentCount how many mails were requested
 */
public record FirstAdministratorInfo(@NotNull UUID id, @NotNull String status, @NotNull Instant expiresAt,
        int sentCount) {
}
