package app.platform.identity.internal;

import app.platform.tenant.TenantContexts;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The one question every request on an organization host asks (ADR-0026): is this person an active member of the
 * organization the host names? The tenant comes from the context the host name produced (never from the client), the
 * membership from the tenant-scoped table, so row level security answers for the same organization the host named.
 * Used at sign-in and on every request that carries a token.
 */
@Component
class MembershipGate {

    private final MembershipRepository memberships;
    private final TenantContexts contexts;
    private final TransactionTemplate transaction;

    MembershipGate(MembershipRepository memberships, TenantContexts contexts, TransactionTemplate transaction) {
        this.memberships = memberships;
        this.contexts = contexts;
        this.transaction = transaction;
    }

    /** Whether the current request is on an organization host (it has a tenant). */
    boolean onOrganizationHost() {
        return contexts.current().isPresent();
    }

    /**
     * The active membership of the user in the organization of the current host.
     *
     * @return empty when the person is not a member, the membership is deactivated, or the request has no tenant
     */
    Optional<MembershipRepository.Own> activeMembership(UUID userId) {
        if (contexts.current().isEmpty()) {
            return Optional.empty();
        }
        Optional<MembershipRepository.Own> found = transaction.execute(status -> memberships.findOwn(userId));
        return found == null ? Optional.empty() : found.filter(MembershipRepository.Own::active);
    }
}
