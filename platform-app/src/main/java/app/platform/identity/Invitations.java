package app.platform.identity;

import java.util.Optional;
import java.util.UUID;

/**
 * What the notification module may know about an invitation when it is time to send the mail (ADR-0028). The queue
 * holds only the facts "which organization, which invitation"; this answers, at send time, whether a mail should go
 * out and in which words, so the invitation request itself never looks at the person's account.
 */
public interface Invitations {

    /**
     * The invitation as it should be mailed now.
     *
     * @param tenantId the inviting organization
     * @param invitationId the invitation
     * @return empty when no mail should go out: the invitation is not open any more (accepted, revoked, expired), the
     *         address already belongs to a member of the organization, or the address belongs to an account that
     *         cannot take part (suspended or closed)
     */
    Optional<InvitationMail> forMail(UUID tenantId, UUID invitationId);
}
