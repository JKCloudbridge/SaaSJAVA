package app.platformapi;

import jakarta.validation.constraints.NotNull;

/**
 * What an invitation link is for, shown to the person who holds the link (who read the mail, so may know this).
 *
 * @param organizationName the inviting organization's name
 * @param email the invited address
 * @param existingAccount whether the address already has an account: the person then signs in and accepts, otherwise
 *        they choose a name and a password
 */
public record InvitationPreview(@NotNull String organizationName, @NotNull String email, boolean existingAccount) {

    @Override
    public String toString() {
        return "InvitationPreview[redacted]";
    }
}
