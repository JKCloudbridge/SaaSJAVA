package app.platform.identity.internal;

import app.platform.identity.InvitationMail;
import app.platform.identity.Invitations;
import app.platform.identity.User;
import app.platform.identity.UserStatus;
import app.platform.identity.Users;
import app.platform.sharedkernel.ActorId;
import app.platform.sharedkernel.TenantId;
import app.platform.sharedkernel.mail.MailQueue;
import app.platform.sharedkernel.mail.MailRequest;
import app.platform.sharedkernel.mail.MailTemplate;
import app.platform.tenant.Tenant;
import app.platform.tenant.TenantContext;
import app.platform.tenant.TenantContexts;
import app.platform.tenant.TenantStatus;
import app.platform.tenant.Tenants;
import app.platformapi.ApiException;
import app.platformapi.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Answers the mail relay's question "should this invitation be mailed, and in which words?" at send time (ADR-0028),
 * and
 * carries out the platform administrator's side of inviting an organization's first administrator (ADR-0037). All the
 * account-state logic of invitations lives in {@link #forMail}, behind the one queue row that every invitation request
 * writes, so what an administrator does never depends on who has an account.
 */
@Service
class DefaultInvitations implements Invitations {

    private final InvitationRepository invitations;
    private final MembershipRepository memberships;
    private final AccountTokenRepository tokens;
    private final Users users;
    private final Tenants tenants;
    private final TenantContexts contexts;
    private final TransactionTemplate transaction;
    private final AccountLimiter limiter;
    private final MailQueue mail;
    private final AuthAudit audit;
    private final IdentityProperties.Account settings;
    private final Clock clock;

    DefaultInvitations(InvitationRepository invitations, MembershipRepository memberships,
            AccountTokenRepository tokens, Users users, Tenants tenants, TenantContexts contexts,
            TransactionTemplate transaction, AccountLimiter limiter, MailQueue mail, AuthAudit audit,
            IdentityProperties properties, Clock clock) {
        this.invitations = invitations;
        this.memberships = memberships;
        this.tokens = tokens;
        this.users = users;
        this.tenants = tenants;
        this.contexts = contexts;
        this.transaction = transaction;
        this.limiter = limiter;
        this.mail = mail;
        this.audit = audit;
        this.settings = properties.account();
        this.clock = clock;
    }

    @Override
    public Optional<InvitationMail> forMail(UUID tenantId, UUID invitationId) {
        TenantId id = new TenantId(tenantId);
        Optional<Tenant> tenant = tenants.findById(id).filter(found -> acceptsMembers(found.status()));
        if (tenant.isEmpty()) {
            return Optional.empty();
        }
        return contexts.call(TenantContext.of(id), () -> transaction.execute(status -> {
            Optional<InvitationRepository.Invitation> open = invitations.find(invitationId)
                    .filter(found -> found.open(clock.instant()));
            if (open.isEmpty()) {
                return Optional.<InvitationMail>empty();
            }
            boolean first = open.get().invitedByPlatform();
            String email = open.get().email();
            Optional<User> account = users.findByEmail(email);
            if (account.isEmpty()) {
                return Optional.of(new InvitationMail(tenant.get().displayName(), email, false, first));
            }
            if (account.get().status() != UserStatus.ACTIVE || memberships.exists(account.get().id())) {
                // A closed account cannot take part; an existing member (active or deactivated) is handled by the
                // administrators, not by a second membership.
                return Optional.<InvitationMail>empty();
            }
            return Optional.of(new InvitationMail(tenant.get().displayName(), email, true, first));
        }));
    }

    @Override
    public void inviteFirstAdministrator(TenantId organization, String emailText, UUID platformActor) {
        String email = Emails.normalize(emailText)
                .orElseThrow(() -> ApiException.validation("email", "Is not a valid address."));
        Tenant tenant = openOrganization(organization);
        limiter.admitInvitation(organization.value(), platformActor, email);
        boolean provisioning = tenant.status() == TenantStatus.PROVISIONING;
        inOrganization(organization, () -> {
            // Identical work for every address: no look at accounts or memberships (ADR-0028, ADR-0037).
            InvitationRepository.Invitation invitation = invitations.openOrRenew(email, provisioning, true, expiry(),
                    new ActorId(platformActor));
            enqueue(organization, invitation.id(), email);
            audit.firstAdministratorInvited(platformActor, invitation.id(), email);
            return null;
        });
    }

    @Override
    public Optional<FirstAdministratorInvitation> firstAdministratorInvitation(TenantId organization) {
        Instant now = clock.instant();
        return inOrganization(organization, invitations::latestByPlatform).map(invitation ->
                new FirstAdministratorInvitation(invitation.id(), statusOf(invitation, now), invitation.expiresAt(),
                        invitation.sentCount()));
    }

    @Override
    public void resendFirstAdministrator(TenantId organization, UUID platformActor) {
        openOrganization(organization);
        InvitationRepository.Invitation current = inOrganization(organization, invitations::latestByPlatform)
                .orElseThrow(() -> ApiException.notFound("There is no invitation to send again."));
        if (!current.open(clock.instant())) {
            throw new ApiException(ErrorCode.CONFLICT, "This invitation is no longer open.");
        }
        limiter.admitInvitation(organization.value(), platformActor, current.email());
        inOrganization(organization, () -> {
            if (!invitations.renew(current.id(), expiry(), new ActorId(platformActor))) {
                throw new ApiException(ErrorCode.CONFLICT, "This invitation is no longer open.");
            }
            enqueue(organization, current.id(), current.email());
            audit.firstAdministratorResent(platformActor, current.id());
            return null;
        });
    }

    @Override
    public int revokeOpenInvitations(TenantId organization, UUID platformActor) {
        return inOrganization(organization, () -> {
            List<UUID> closed = invitations.revokeAllOpen(new ActorId(platformActor));
            closed.forEach(tokens::cancelOpenOfInvitation);
            audit.invitationsRevokedByPlatform(platformActor, closed.size());
            return closed.size();
        });
    }

    /** An organization that is being set up or open; anything else cannot take a first administrator. */
    private Tenant openOrganization(TenantId organization) {
        Tenant tenant = tenants.findById(organization)
                .orElseThrow(() -> ApiException.notFound("The organization was not found."));
        if (!acceptsMembers(tenant.status())) {
            throw new ApiException(ErrorCode.CONFLICT, "The organization is not open or being set up.");
        }
        return tenant;
    }

    private static boolean acceptsMembers(TenantStatus status) {
        return status == TenantStatus.ACTIVE || status == TenantStatus.PROVISIONING;
    }

    /** Runs the work in one transaction under the organization's context (the caller's, when it is the same one). */
    private <T> T inOrganization(TenantId organization, Supplier<T> work) {
        TenantContext context = contexts.current().filter(current -> current.tenantId().equals(organization))
                .orElseGet(() -> TenantContext.of(organization));
        return contexts.call(context, () -> transaction.execute(status -> work.get()));
    }

    private Instant expiry() {
        return clock.instant().plus(settings.invitationLinkLife());
    }

    private void enqueue(TenantId organization, UUID invitationId, String email) {
        mail.enqueue(MailRequest.of(MailTemplate.INVITATION, email)
                .with("organization_id", organization.value().toString())
                .with("invitation_id", invitationId.toString()));
    }

    private static String statusOf(InvitationRepository.Invitation invitation, Instant now) {
        if (InvitationRepository.OPEN.equals(invitation.status()) && !invitation.expiresAt().isAfter(now)) {
            return "EXPIRED";
        }
        return invitation.status();
    }
}
