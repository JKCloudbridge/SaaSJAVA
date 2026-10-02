package app.platform.identity;

import java.time.Duration;
import java.util.UUID;

/**
 * Creates the one-time token of a link that is e-mailed to a person (ADR-0023): a random secret that is single use,
 * expires, and is stored only as a hash.
 *
 * <p><strong>Only the notification module may call this</strong> (an architecture rule enforces it): the token is
 * created at the moment the mail is sent, so the mail queue never holds a secret. Creating a token cancels the older
 * unused tokens of the same purpose for the same address, so only the newest link works. The raw token is returned
 * once and is never stored, logged or audited; whoever receives it puts it into the mail and drops it.
 */
public interface AccountTokens {

    /**
     * Creates a token.
     *
     * @param purpose what the token is for
     * @param email the address the link is sent to, normalized
     * @param userId the account the token is for; required for {@link AccountTokenPurpose#PASSWORD_RESET}, null for a
     *        sign-up (the address has no account yet)
     * @return the token to put into the link
     * @throws IllegalArgumentException for {@link AccountTokenPurpose#INVITATION}: use {@link #issueInvitation}
     */
    String issue(AccountTokenPurpose purpose, String email, UUID userId);

    /**
     * Creates the token of an invitation link (ADR-0028). It resolves, on the server, to the organization and the
     * invitation, and cancels the older unused links of the same invitation, so only the newest link works.
     *
     * @param email the invited address, normalized
     * @param tenantId the inviting organization
     * @param invitationId the invitation
     * @return the token to put into the link
     */
    String issueInvitation(String email, UUID tenantId, UUID invitationId);

    /** How long a token of this purpose works, for the text of the mail that carries it. */
    Duration lifetime(AccountTokenPurpose purpose);
}
