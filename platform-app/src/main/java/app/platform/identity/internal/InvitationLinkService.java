package app.platform.identity.internal;

import app.platform.identity.AccountTokenPurpose;
import app.platform.identity.User;
import app.platform.identity.UserStatus;
import app.platform.identity.Users;
import app.platform.security.MemberAccess;
import app.platform.sharedkernel.ActorId;
import app.platform.sharedkernel.TenantId;
import app.platform.tenant.Tenant;
import app.platform.tenant.TenantContext;
import app.platform.tenant.TenantContexts;
import app.platform.tenant.TenantStatus;
import app.platform.tenant.Tenants;
import app.platformapi.ApiException;
import app.platformapi.ErrorCode;
import app.platformapi.InvitationAccepted;
import app.platformapi.InvitationPreview;
import java.time.Clock;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The invited person's side of an invitation (ADR-0028): read what the link is for, accept as a new person (choose a
 * name and a password), accept as a person who already has an account (signed in as the invited address).
 *
 * <p>The link's token resolves, on the server, to the invitation and so to the organization; nothing the person sends
 * can name or change the organization. A link that cannot be used (unknown, used, replaced, revoked, expired, the
 * organization closed, the address already a member, the wrong signed-in person) is always the same answer, and its
 * true reason is audited after the transaction ended. Acceptance runs in one transaction under the organization's
 * context: the token is used first (so simultaneous uses have one winner), the invitation is locked, and a membership
 * is created only then.
 */
@Service
class InvitationLinkService {

    static final String INVALID_LINK = "This link is not valid or has expired.";

    private final AccountTokenRepository tokens;
    private final InvitationRepository invitations;
    private final MembershipRepository memberships;
    private final Users users;
    private final MemberAccess access;
    private final Tenants tenants;
    private final TenantContexts contexts;
    private final AccountLimiter limiter;
    private final AuthAudit audit;
    private final OrganizationHosts hosts;
    private final TransactionTemplate transaction;
    private final Clock clock;

    InvitationLinkService(AccountTokenRepository tokens, InvitationRepository invitations,
            MembershipRepository memberships, Users users, MemberAccess access, Tenants tenants,
            TenantContexts contexts, AccountLimiter limiter, AuthAudit audit, OrganizationHosts hosts,
            TransactionTemplate transaction, Clock clock) {
        this.tokens = tokens;
        this.invitations = invitations;
        this.memberships = memberships;
        this.users = users;
        this.access = access;
        this.tenants = tenants;
        this.contexts = contexts;
        this.limiter = limiter;
        this.audit = audit;
        this.hosts = hosts;
        this.transaction = transaction;
        this.clock = clock;
    }

    /** What the invitation is for and which way the person goes (choose a password, or sign in and accept). */
    InvitationPreview preview(String token, String source) {
        limiter.admitTokenAttempt(source);
        AccountTokenRepository.Live live = liveOrRefuse(token, source);
        Resolved resolved = resolve(live).orElseThrow(() -> refused("invitation_not_open", source));
        return new InvitationPreview(resolved.tenant().displayName(), live.email(),
                users.findByEmail(live.email()).isPresent(), resolved.invitation().displayName());
    }

    /**
     * A person without an account accepts: the account is created with the chosen name and password, and the
     * membership with it.
     *
     * @throws ApiException {@code VALIDATION_ERROR} on the field {@code token} for any link that cannot be used
     *         (including an address that has an account by now), on {@code password} or {@code displayName} for
     *         unacceptable text (the link stays usable), {@code RATE_LIMITED}
     */
    InvitationAccepted acceptNew(String token, String displayName, char[] password, String source,
            String authority) {
        try {
            limiter.admitTokenAttempt(source);
            AccountTokenRepository.Live live = liveOrRefuse(token, source);
            return finish(live, null, displayName, password, source, authority);
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    /**
     * A signed-in person accepts. Only the person the invitation was sent to can: anybody else gets the same answer as
     * for an unusable link, and the link stays usable for the right person.
     */
    InvitationAccepted acceptExisting(String token, UUID signedInUser, String source, String authority) {
        limiter.admitTokenAttempt(source);
        AccountTokenRepository.Live live = liveOrRefuse(token, source);
        User user = users.findById(signedInUser).filter(found -> found.status() == UserStatus.ACTIVE)
                .orElseThrow(() -> refused("not_signed_in_as_active_user", source));
        if (!user.email().equalsIgnoreCase(live.email())) {
            throw refused("wrong_person", source);
        }
        return finish(live, user, null, null, source, authority);
    }

    private InvitationAccepted finish(AccountTokenRepository.Live live, User existing, String displayName,
            char[] password, String source, String authority) {
        TenantId tenantId = new TenantId(live.contextTenantId());
        Resolved[] outcome = new Resolved[1];
        try {
            contexts.run(TenantContext.of(tenantId), () -> transaction.executeWithoutResult(status -> {
                if (!tokens.consume(live.id(), clock.instant())) {
                    throw new LinkRefusal("already_used");
                }
                Resolved resolved = resolve(live).orElseThrow(() -> new LinkRefusal("invitation_not_open"));
                InvitationRepository.Invitation invitation = invitations.findForUpdate(live.invitationId())
                        .filter(found -> found.open(clock.instant()))
                        .orElseThrow(() -> new LinkRefusal("invitation_not_open"));
                User person = existing;
                if (person == null) {
                    // The name the administrator entered wins; otherwise the person chose one (a first administrator
                    // invited by the platform has none entered).
                    String name = invitation.displayName() != null ? invitation.displayName() : displayName;
                    if (name == null || name.isBlank()) {
                        throw ApiException.validation("displayName", "Must not be empty.");
                    }
                    try {
                        person = users.createActive(invitation.email(), name, password, ActorId.SYSTEM);
                    } catch (ApiException e) {
                        if (e.code() == ErrorCode.CONFLICT) {
                            // The address got an account by another way since the link was sent: sign in and accept.
                            throw new LinkRefusal("address_has_account");
                        }
                        throw e;
                    }
                }
                if (memberships.exists(person.id())) {
                    throw new LinkRefusal("already_member");
                }
                ActorId actor = new ActorId(person.id());
                UUID membership = memberships.insertMember(person.id(), invitation.founding(), actor);
                invitations.accept(invitation.id(), membership, actor);
                tokens.cancelOpenOfInvitation(invitation.id());
                audit.invitationAccepted(person.id(), invitation.id(), membership, existing == null);
                if (resolved.tenant().status() == TenantStatus.PROVISIONING) {
                    // The first administrator of an organization a platform administrator set up: accepting opens it,
                    // in the same transaction, so the organization is never open without its administrator.
                    openProvisioned(tenantId, actor, person.id());
                }
                // The person gets the profile and the role the administrator chose (the administrator profile for an
                // invitation made by a platform administrator or before profiles existed, the default profile
                // otherwise) and the licence of the profile's type when one is free. Never a reason to refuse them:
                // without a licence they join without the profile's abilities until one is free (ADR-0039). The
                // first administrator of a platform-provisioned organization always gets an administrator licence.
                access.join(membership, invitation.profileId(), invitation.roleId(),
                        invitation.administrator() || invitation.invitedByPlatform(), invitation.invitedByPlatform(),
                        actor);
                outcome[0] = resolved;
            }));
        } catch (LinkRefusal e) {
            // Recorded after the transaction ended: a record written inside it would roll back with it.
            throw refused(e.reason(), source);
        }
        Tenant tenant = outcome[0].tenant();
        return new InvitationAccepted(tenant.slug().value(), tenant.displayName(),
                hosts.of(tenant.slug().value(), authority));
    }

    private void openProvisioned(TenantId tenantId, ActorId actor, UUID person) {
        try {
            tenants.activate(tenantId, actor);
        } catch (ApiException e) {
            if (e.code() == ErrorCode.CONFLICT) {
                // The organization was closed between reading it and now: the same answer as any unusable link.
                throw new LinkRefusal("organization_not_open");
            }
            throw e;
        }
        audit.organizationOpened(person);
    }

    /** The open invitation behind a live token, and its organization (open, or being set up for its first admin). */
    private Optional<Resolved> resolve(AccountTokenRepository.Live live) {
        TenantId tenantId = new TenantId(live.contextTenantId());
        Optional<Tenant> tenant = tenants.findById(tenantId).filter(found -> found.status() == TenantStatus.ACTIVE
                || found.status() == TenantStatus.PROVISIONING);
        if (tenant.isEmpty()) {
            return Optional.empty();
        }
        Optional<InvitationRepository.Invitation> invitation = contexts.call(TenantContext.of(tenantId),
                () -> transaction.execute(status -> invitations.find(live.invitationId())))
                .filter(found -> found.open(clock.instant()))
                .filter(found -> found.email().equals(live.email()));
        return invitation.map(found -> new Resolved(tenant.get(), found));
    }

    private AccountTokenRepository.Live liveOrRefuse(String token, String source) {
        return tokens.findLive(Hashes.hashed(token), AccountTokenPurpose.INVITATION, clock.instant())
                .filter(found -> found.contextTenantId() != null && found.invitationId() != null)
                .orElseThrow(() -> refused("not_live", source));
    }

    private ApiException refused(String reason, String source) {
        audit.linkRefused("INVITATION", reason, source);
        return ApiException.validation("token", INVALID_LINK);
    }

    private record Resolved(Tenant tenant, InvitationRepository.Invitation invitation) {
    }
}
