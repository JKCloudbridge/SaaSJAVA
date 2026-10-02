package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

/**
 * An invitation as the organization's administrators see it.
 *
 * @param id the invitation
 * @param email the invited address
 * @param administrator whether the person becomes an administrator on accepting
 * @param status {@code OPEN}, {@code EXPIRED}, {@code ACCEPTED} or {@code REVOKED}
 * @param expiresAt when the link stops working
 * @param sentCount how many times the e-mail was requested
 * @param createdAt when the address was first invited
 */
public record InvitationView(@NotNull UUID id, @NotNull String email, boolean administrator, @NotNull String status,
        @NotNull Instant expiresAt, int sentCount, @NotNull Instant createdAt) {

    @Override
    public String toString() {
        return "InvitationView[redacted]";
    }
}
