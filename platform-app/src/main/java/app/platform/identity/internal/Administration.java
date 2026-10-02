package app.platform.identity.internal;

import app.platform.tenant.TenantContext;
import app.platform.tenant.TenantContexts;
import app.platformapi.ApiException;
import app.platformapi.ErrorCode;
import java.util.function.Function;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The one small question behind every administrative action of an organization: "may this member administer the
 * organization?" (ADR-0026).
 *
 * <p>In Sprint 5 the answer is the changeable administrator marker on an active membership. It is a stop-gap: Sprint 7
 * replaces the marker by an access policy, and only this class changes. The caller is the person behind the request
 * (never a name taken from the request), in the organization the host names (never one named by the client).
 */
@Component
class Administration {

    private final MembershipRepository memberships;
    private final TenantContexts contexts;
    private final TransactionTemplate transaction;
    private final AuthAudit audit;

    Administration(MembershipRepository memberships, TenantContexts contexts, TransactionTemplate transaction,
            AuthAudit audit) {
        this.memberships = memberships;
        this.contexts = contexts;
        this.transaction = transaction;
        this.audit = audit;
    }

    /**
     * Runs an administrative action in one transaction, after checking inside that transaction that the caller is an
     * administrator. A refusal is recorded after the transaction ended (a record written inside it would roll back).
     *
     * @param action a short code for the audit record
     * @param work what to do, given the caller's own membership
     * @throws ApiException {@code NOT_FOUND} on the platform host (there is no organization), {@code FORBIDDEN} for a
     *         caller who is not an administrator of this organization
     */
    <T> T run(String action, Function<MembershipRepository.Own, T> work) {
        TenantContext context = contexts.current().filter(c -> c.userId() != null)
                .orElseThrow(() -> ApiException.notFound("This is not available at this address."));
        try {
            return transaction.execute(status -> {
                MembershipRepository.Own caller = memberships.findOwn(context.userId())
                        .filter(MembershipRepository.Own::active)
                        .filter(MembershipRepository.Own::administrator)
                        .orElseThrow(() -> new ApiException(ErrorCode.FORBIDDEN));
                return work.apply(caller);
            });
        } catch (ApiException e) {
            if (e.code() == ErrorCode.FORBIDDEN) {
                audit.membershipActionRefused(context.userId(), action, "not_an_administrator");
            }
            throw e;
        }
    }
}
