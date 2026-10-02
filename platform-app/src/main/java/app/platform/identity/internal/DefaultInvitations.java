package app.platform.identity.internal;

import app.platform.identity.InvitationMail;
import app.platform.identity.Invitations;
import app.platform.identity.User;
import app.platform.identity.UserStatus;
import app.platform.identity.Users;
import app.platform.sharedkernel.TenantId;
import app.platform.tenant.Tenant;
import app.platform.tenant.TenantContext;
import app.platform.tenant.TenantContexts;
import app.platform.tenant.TenantStatus;
import app.platform.tenant.Tenants;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Answers the mail relay's question "should this invitation be mailed, and in which words?" at send time (ADR-0028).
 * All
 * the account-state logic of invitations lives here, behind the one queue row that every invitation request writes, so
 * the request the administrator makes never depends on who has an account.
 */
@Service
class DefaultInvitations implements Invitations {

    private final InvitationRepository invitations;
    private final MembershipRepository memberships;
    private final Users users;
    private final Tenants tenants;
    private final TenantContexts contexts;
    private final TransactionTemplate transaction;
    private final Clock clock;

    DefaultInvitations(InvitationRepository invitations, MembershipRepository memberships, Users users,
            Tenants tenants, TenantContexts contexts, TransactionTemplate transaction, Clock clock) {
        this.invitations = invitations;
        this.memberships = memberships;
        this.users = users;
        this.tenants = tenants;
        this.contexts = contexts;
        this.transaction = transaction;
        this.clock = clock;
    }

    @Override
    public Optional<InvitationMail> forMail(UUID tenantId, UUID invitationId) {
        TenantId id = new TenantId(tenantId);
        Optional<Tenant> tenant = tenants.findById(id).filter(found -> found.status() == TenantStatus.ACTIVE);
        if (tenant.isEmpty()) {
            return Optional.empty();
        }
        return contexts.call(TenantContext.of(id), () -> transaction.execute(status -> {
            Optional<InvitationRepository.Invitation> open = invitations.find(invitationId)
                    .filter(found -> found.open(clock.instant()));
            if (open.isEmpty()) {
                return Optional.<InvitationMail>empty();
            }
            String email = open.get().email();
            Optional<User> account = users.findByEmail(email);
            if (account.isEmpty()) {
                return Optional.of(new InvitationMail(tenant.get().displayName(), email, false));
            }
            if (account.get().status() != UserStatus.ACTIVE || memberships.exists(account.get().id())) {
                // A closed account cannot take part; an existing member (active or deactivated) is handled by the
                // administrators, not by a second membership.
                return Optional.<InvitationMail>empty();
            }
            return Optional.of(new InvitationMail(tenant.get().displayName(), email, true));
        }));
    }
}
