package app.platform.security.internal;

import app.platform.security.Ability;
import app.platform.security.LastAccessManager;
import app.platform.security.Permissions;
import app.platform.tenant.TenantContext;
import app.platform.tenant.TenantContexts;
import app.platformapi.ApiException;
import app.platformapi.ErrorCode;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The question behind every endpoint of this module: "does the caller hold the ability this action needs?" (ADR-0039).
 * The caller is the person behind the request, in the organization the host names, never one named by the client. The
 * check runs inside the transaction of the work, so the answer and the action cannot drift apart; a refusal is recorded
 * after the transaction ended (a record written inside it would roll back).
 *
 * <p>A platform person who opened an organization's context has no membership and so no abilities: a platform role
 * gives
 * no authority inside an organization.
 */
@Component
class AccessGate {

    /** The person who asks. */
    record Caller(UUID userId, UUID membershipId) {
    }

    private final TenantContexts contexts;
    private final TransactionTemplate transaction;
    private final Permissions permissions;
    private final AccessAudit audit;

    AccessGate(TenantContexts contexts, TransactionTemplate transaction, Permissions permissions,
            AccessAudit audit) {
        this.contexts = contexts;
        this.transaction = transaction;
        this.permissions = permissions;
        this.audit = audit;
    }

    /**
     * Runs the work in one transaction after checking, inside it, that the caller holds at least one of the abilities.
     *
     * @param action a short code for the audit record of a refusal
     * @param anyOf the abilities of which one is enough
     * @throws ApiException {@code NOT_FOUND} on the platform host (there is no organization), {@code FORBIDDEN} for a
     *         caller without the ability, {@code CONFLICT} when the work would leave the organization with nobody who
     *         can manage access
     */
    <T> T run(String action, Set<Ability> anyOf, Function<Caller, T> work) {
        TenantContext context = contexts.current().filter(current -> current.userId() != null)
                .orElseThrow(() -> ApiException.notFound("This is not available at this address."));
        try {
            return transaction.execute(status -> {
                UUID membership = context.membershipId();
                Set<Ability> held = permissions.effective(membership);
                if (membership == null || anyOf.stream().noneMatch(held::contains)) {
                    throw new ApiException(ErrorCode.FORBIDDEN);
                }
                return work.apply(new Caller(context.userId(), membership));
            });
        } catch (ApiException e) {
            if (e.code() == ErrorCode.FORBIDDEN) {
                audit.refused(context.userId(), action, "missing_ability");
            }
            throw e;
        } catch (RuntimeException e) {
            if (LastAccessManager.isViolation(e)) {
                throw new ApiException(ErrorCode.CONFLICT, LastAccessManager.MESSAGE);
            }
            throw e;
        }
    }
}
