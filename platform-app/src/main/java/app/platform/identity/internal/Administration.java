package app.platform.identity.internal;

import app.platform.identity.OrganizationAdministration;
import app.platform.security.Ability;
import app.platform.security.LastAccessManager;
import app.platform.security.Permissions;
import app.platform.tenant.TenantContext;
import app.platform.tenant.TenantContexts;
import app.platformapi.ApiException;
import app.platformapi.ErrorCode;
import java.util.function.Function;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The one small question behind every administrative action of an organization: "may this member do this?" (ADR-0026,
 * ADR-0039). Since Sprint 7 the answer is the member's effective abilities (their licensed profile, their access
 * policies and their individual grants, computed by the security module); the changeable administrator marker of Sprint
 * 5 decides nothing any more. This is the only class of the identity module that asks. The caller is the person behind
 * the request (never a name taken from the request), in the organization the host names (never one named by the
 * client).
 */
@Component
class Administration implements OrganizationAdministration {

    private final MembershipRepository memberships;
    private final TenantContexts contexts;
    private final TransactionTemplate transaction;
    private final AuthAudit audit;
    private final Permissions permissions;

    Administration(MembershipRepository memberships, TenantContexts contexts, TransactionTemplate transaction,
            AuthAudit audit, Permissions permissions) {
        this.memberships = memberships;
        this.contexts = contexts;
        this.transaction = transaction;
        this.audit = audit;
        this.permissions = permissions;
    }

    @Override
    public <T> T asAdministrator(String action, Ability ability, Function<Caller, T> work) {
        return run(action, ability, own -> work.apply(new Caller(own.userId(), own.id())));
    }

    /**
     * Runs an action in one transaction, after checking inside that transaction that the caller is an active member who
     * holds the ability. A refusal is recorded after the transaction ended (a record written inside it would roll
     * back).
     *
     * @param action a short code for the audit record
     * @param ability the ability the action needs
     * @param work what to do, given the caller's own membership
     * @throws ApiException {@code NOT_FOUND} on the platform host (there is no organization), {@code FORBIDDEN} for a
     *         caller who lacks the ability, {@code CONFLICT} when the work would leave nobody who can manage access
     */
    <T> T run(String action, Ability ability, Function<MembershipRepository.Own, T> work) {
        TenantContext context = contexts.current().filter(c -> c.userId() != null)
                .orElseThrow(() -> ApiException.notFound("This is not available at this address."));
        try {
            return transaction.execute(status -> {
                MembershipRepository.Own caller = memberships.findOwn(context.userId())
                        .filter(MembershipRepository.Own::active)
                        .filter(own -> permissions.has(own.id(), ability))
                        .orElseThrow(() -> new ApiException(ErrorCode.FORBIDDEN));
                return work.apply(caller);
            });
        } catch (ApiException e) {
            if (e.code() == ErrorCode.FORBIDDEN) {
                audit.membershipActionRefused(context.userId(), action, "missing_ability");
            }
            throw e;
        } catch (RuntimeException e) {
            // The database refuses, when the transaction commits, to leave an organization with nobody who can manage
            // access (ADR-0044); the person is told in words, never the database's.
            if (LastAccessManager.isViolation(e)) {
                throw new ApiException(ErrorCode.CONFLICT, LastAccessManager.MESSAGE);
            }
            throw e;
        }
    }
}
