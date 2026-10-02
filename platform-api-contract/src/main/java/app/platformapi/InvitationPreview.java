package app.platformapi;

import jakarta.validation.constraints.NotNull;

/**
 * What an invitation link is for, shown to the person who holds the link (who read the mail, so may know this).
 *
 * @param organizationName the inviting organization's name
 * @param email the invited address
 * @param existingAccount whether the address already has an account: the person then signs in and accepts, otherwise
 *        they choose a password (and a name when the invitation holds none)
 * @param displayName the name the administrator entered for the person, absent when the person chooses it
 */
public record InvitationPreview(@NotNull String organizationName, @NotNull String email, boolean existingAccount,
        String displayName) {

    @Override
    public String toString() {
        return "InvitationPreview[redacted]";
    }
}
