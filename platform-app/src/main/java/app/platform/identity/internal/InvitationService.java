package app.platform.identity.internal;

import app.platform.security.Ability;
import app.platform.security.MemberAccess;
import app.platform.sharedkernel.ActorId;
import app.platform.sharedkernel.mail.MailQueue;
import app.platform.sharedkernel.mail.MailRequest;
import app.platform.sharedkernel.mail.MailTemplate;
import app.platform.tenant.TenantContexts;
import app.platformapi.ApiException;
import app.platformapi.ErrorCode;
import app.platformapi.InvitationView;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * An organization's administrators invite people (ADR-0028). An invitation is a record of the organization; it grants
 * nothing until the person accepts, and then a membership is created. The e-mailed link resolves to the record on the
 * server, so the organization never comes from the browser.
 *
 * <p><strong>Asking for an invitation does identical work for every address</strong> (the rule of ADR-0023): a format
 * check, the limits, one invitation row, one queue row and one audit record, with no look at the user table or the
 * memberships. Whether a mail goes out, and which one (a new account or an existing one), is decided later by the mail
 * relay, where nobody waits for it. So the administrator cannot use this to find out who has an account.
 */
@Service
class InvitationService {

    private final Administration administration;
    private final MemberAccess access;
    private final InvitationRepository invitations;
    private final AccountTokenRepository tokens;
    private final AccountLimiter limiter;
    private final MailQueue mail;
    private final AuthAudit audit;
    private final TenantContexts contexts;
    private final IdentityProperties.Account settings;
    private final Clock clock;

    InvitationService(Administration administration, MemberAccess access, InvitationRepository invitations,
            AccountTokenRepository tokens, AccountLimiter limiter, MailQueue mail, AuthAudit audit,
            TenantContexts contexts, IdentityProperties properties, Clock clock) {
        this.administration = administration;
        this.access = access;
        this.invitations = invitations;
        this.tokens = tokens;
        this.limiter = limiter;
        this.mail = mail;
        this.audit = audit;
        this.contexts = contexts;
        this.settings = properties.account();
        this.clock = clock;
    }

    /**
     * Invites an address, or sends the open invitation again. The caller answers the same way whatever this does.
     *
     * @throws ApiException {@code VALIDATION_ERROR} for text that cannot be an address (the same for every address),
     *         {@code FORBIDDEN} for a caller who is not an administrator, {@code RATE_LIMITED}
     */
    void invite(String emailText, String displayNameText, UUID profileId, UUID roleId, boolean send) {
        String email = Emails.normalize(emailText)
                .orElseThrow(() -> ApiException.validation("email", "Is not a valid address."));
        String displayName = PersonNames.clean(displayNameText);
        // Who may ask is decided first; the limits then count the request before anything about the address is read.
        MembershipRepository.Own caller = administration.run("invitation.create", Ability.MEMBERS_INVITE, own -> own);
        UUID tenant = contexts.require().tenantId().value();
        limiter.admitInvitation(tenant, caller.userId(), email);
        administration.run("invitation.create", Ability.MEMBERS_INVITE, own -> {
            // The profile and the role are checked inside the transaction and never depend on the address; a person who
            // cannot manage access can only give a profile whose abilities they hold themselves (ADR-0043).
            UUID profile = access.checkInvitation(own.id(), profileId, roleId);
            InvitationRepository.Invitation invitation = invitations.openOrRenew(email, profile, roleId, displayName,
                    send, expiry(), new ActorId(own.userId()));
            if (send) {
                enqueue(tenant, invitation.id(), email);
            }
            audit.invitationRequested(own.userId(), invitation.id(), email, profile, send);
            return null;
        });
    }

    /** The organization's invitations, newest first, with the profile and role each will give. */
    List<InvitationView> list() {
        Instant now = clock.instant();
        return administration.run("invitation.list", Ability.MEMBERS_INVITE, own -> {
            Map<UUID, String> profiles = access.profileNames();
            Map<UUID, String> roles = access.roleNames();
            return invitations.list().stream()
                    .map(invitation -> new InvitationView(invitation.id(), invitation.email(),
                            invitation.displayName(), invitation.profileId(), profiles.get(invitation.profileId()),
                            roles.get(invitation.roleId()), statusOf(invitation, now), invitation.expiresAt(),
                            invitation.sentCount(), invitation.createdAt()))
                    .toList();
        });
    }

    /**
     * Sends an open invitation again: a new link replaces the old one and the time starts again.
     *
     * @throws ApiException {@code NOT_FOUND} for an invitation of another organization or none, {@code CONFLICT} when
     * it
     *         is no longer open
     */
    void resend(UUID invitationId) {
        UUID tenant = contexts.require().tenantId().value();
        record Checked(MembershipRepository.Own caller, InvitationRepository.Invitation invitation) {
        }
        Checked checked = administration.run("invitation.resend", Ability.MEMBERS_INVITE, own -> {
            InvitationRepository.Invitation invitation = invitations.find(invitationId)
                    .orElseThrow(() -> ApiException.notFound("This invitation does not exist."));
            if (!invitation.open(clock.instant())) {
                throw new ApiException(ErrorCode.CONFLICT, "This invitation is no longer open.");
            }
            return new Checked(own, invitation);
        });
        InvitationRepository.Invitation current = checked.invitation();
        limiter.admitInvitation(tenant, checked.caller().userId(), current.email());
        administration.run("invitation.resend", Ability.MEMBERS_INVITE, own -> {
            if (!invitations.renew(invitationId, expiry(), new ActorId(own.userId()))) {
                throw new ApiException(ErrorCode.CONFLICT, "This invitation is no longer open.");
            }
            enqueue(tenant, invitationId, current.email());
            audit.invitationResent(own.userId(), invitationId);
            return null;
        });
    }

    /**
     * Withdraws an open invitation: its link stops working at once.
     *
     * @throws ApiException {@code NOT_FOUND} for an invitation of another organization or none, {@code CONFLICT} when
     * it
     *         is no longer open
     */
    void revoke(UUID invitationId) {
        administration.run("invitation.revoke", Ability.MEMBERS_INVITE, own -> {
            invitations.find(invitationId).orElseThrow(() -> ApiException.notFound("This invitation does not exist."));
            if (!invitations.revoke(invitationId, new ActorId(own.userId()))) {
                throw new ApiException(ErrorCode.CONFLICT, "This invitation is no longer open.");
            }
            tokens.cancelOpenOfInvitation(invitationId);
            audit.invitationRevoked(own.userId(), invitationId);
            return null;
        });
    }

    private Instant expiry() {
        return clock.instant().plus(settings.invitationLinkLife());
    }

    private void enqueue(UUID tenant, UUID invitationId, String email) {
        mail.enqueue(MailRequest.of(MailTemplate.INVITATION, email)
                .with("organization_id", tenant.toString())
                .with("invitation_id", invitationId.toString()));
    }

    private static String statusOf(InvitationRepository.Invitation invitation, Instant now) {
        if (InvitationRepository.OPEN.equals(invitation.status()) && !invitation.expiresAt().isAfter(now)) {
            return "EXPIRED";
        }
        return invitation.status();
    }
}
