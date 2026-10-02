package app.platform.licensing.internal;

import app.platform.sharedkernel.TenantId;
import app.platform.tenant.TenantContext;
import app.platform.tenant.TenantContexts;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs work in one transaction under one organization's tenant context, so row level security limits it to that
 * organization (ADR-0015). When the thread already works for that organization (a request on its host, a founding in
 * progress) its own context and transaction are joined, keeping the caller's user; the context of another organization
 * is refused by the tenant module, never silently replaced.
 */
@Component
class OrganizationWork {

    private final TenantContexts contexts;
    private final TransactionTemplate transaction;

    OrganizationWork(TenantContexts contexts, TransactionTemplate transaction) {
        this.contexts = contexts;
        this.transaction = transaction;
    }

    /** The organization the thread works for now. */
    TenantId current() {
        return contexts.require().tenantId();
    }

    /** Runs the work for the organization the thread works for now, in a transaction (joined when one runs). */
    <T> T inCurrent(Supplier<T> work) {
        contexts.require();
        return transaction.execute(status -> work.get());
    }

    /** Runs the work for the named organization, in a transaction. */
    <T> T in(TenantId tenant, Supplier<T> work) {
        TenantContext context = contexts.current()
                .filter(current -> current.tenantId().equals(tenant))
                .orElseGet(() -> TenantContext.of(tenant));
        return contexts.call(context, () -> transaction.execute(status -> work.get()));
    }
}
