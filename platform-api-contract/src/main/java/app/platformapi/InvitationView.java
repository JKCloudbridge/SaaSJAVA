package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

/**
 * An invitation as the organization's administrators see it.
 *
 * @param id the invitation
 * @param email the invited address
 * @param displayName the name the administrator entered, absent when the person chooses it
 * @param profileId the profile the member gets on accepting, absent for the default profile
 * @param profileName the profile's name
 * @param roleName the role the member gets, absent for none
 * @param status {@code OPEN}, {@code EXPIRED}, {@code ACCEPTED} or {@code REVOKED}
 * @param expiresAt when the link stops working
 * @param sentCount how many times the e-mail was requested; zero for an invitation that was saved but not sent
 * @param createdAt when the address was first invited
 */
public record InvitationView(@NotNull UUID id, @NotNull String email, String displayName, UUID profileId,
        String profileName, String roleName, @NotNull String status, @NotNull Instant expiresAt, int sentCount,
        @NotNull Instant createdAt) {

    @Override
    public String toString() {
        return "InvitationView[redacted]";
    }
}
