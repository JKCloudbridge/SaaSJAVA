package app.platform.identity;

import app.platform.sharedkernel.TenantId;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Invitations as the rest of the platform sees them: what the notification module may know when it is time to send the
 * mail (ADR-0028), and the invitation of an organization's first administrator by a platform administrator (ADR-0037).
 */
public interface Invitations {

    /**
     * The state of the first-administrator invitation of an organization, for the console. It never carries the
     * address.
     *
     * @param id the invitation
     * @param status {@code OPEN}, {@code EXPIRED}, {@code ACCEPTED} or {@code REVOKED}
     * @param expiresAt when it ends
     * @param sentCount how many mails were requested for it
     */
    record FirstAdministratorInvitation(UUID id, String status, Instant expiresAt, int sentCount) {
    }

    /**
     * The invitation as it should be mailed now.
     *
     * @param tenantId the inviting organization
     * @param invitationId the invitation
     * @return empty when no mail should go out: the invitation is not open any more (accepted, revoked, expired), the
     *         address already belongs to a member of the organization, the organization is closed, or the address
     *         belongs to an account that cannot take part (suspended or closed)
     */
    Optional<InvitationMail> forMail(UUID tenantId, UUID invitationId);

    /**
     * A platform administrator invites the first administrator of an organization that is still being set up (or the
     * new administrator of one that lost every administrator). Does identical work for every address: no look at
     * accounts, one invitation row, one queue row, one audit record, so the caller cannot find out whether the address
     * has an account (the mail decides later, at send time). The invitation grants nothing until accepted; accepting
     * it opens a PROVISIONING organization.
     *
     * <p>Runs under the named organization's tenant context (opened here, or the one the caller already opened for
     * it before its transaction), so row level security limits the write to it.
     *
     * @param organization the organization, chosen by an authorized platform administrator (never a request's tenant)
     * @param email the address to invite
     * @param platformActor the platform administrator
     * @throws app.platformapi.ApiException {@code VALIDATION_ERROR} for text that cannot be an address,
     *         {@code NOT_FOUND} for an unknown organization, {@code CONFLICT} for a closed one, {@code RATE_LIMITED}
     */
    void inviteFirstAdministrator(TenantId organization, String email, UUID platformActor);

    /** The latest first-administrator invitation of the organization, if there is one. */
    Optional<FirstAdministratorInvitation> firstAdministratorInvitation(TenantId organization);

    /**
     * Sends the open first-administrator invitation again (a new link replaces the old one and the time starts again).
     *
     * @throws app.platformapi.ApiException {@code NOT_FOUND} when there is none, {@code CONFLICT} when it is no longer
     *         open, {@code RATE_LIMITED}
     */
    void resendFirstAdministrator(TenantId organization, UUID platformActor);

    /**
     * Withdraws every open invitation of the organization (cancelling a provisioning, or closing an organization).
     *
     * @return how many were open
     */
    int revokeOpenInvitations(TenantId organization, UUID platformActor);
}
