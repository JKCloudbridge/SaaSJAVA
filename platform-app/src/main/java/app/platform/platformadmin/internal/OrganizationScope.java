package app.platform.platformadmin.internal;

import app.platform.sharedkernel.TenantId;
import app.platform.tenant.TenantContext;
import app.platform.tenant.TenantContexts;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * How a platform person reaches one organization without a privileged connection (ADR-0031): the code opens that one
 * organization's tenant context before a transaction begins, so row level security limits every statement of the
 * transaction to it. One organization at a time, chosen by an authorized platform person; lists across organizations
 * come only from platform-level tables, never from here. The caller (platform person) travels in the context, so logs
 * and records name them.
 */
@Component
class OrganizationScope {

    private final TenantContexts contexts;
    private final TransactionTemplate transaction;

    OrganizationScope(TenantContexts contexts, TransactionTemplate transaction) {
        this.contexts = contexts;
        this.transaction = transaction;
    }

    /** Runs the work in one transaction under the organization's context, on behalf of the platform person. */
    <T> T in(TenantId organization, UUID platformUser, Supplier<T> work) {
        TenantContext context = new TenantContext(organization, platformUser, null);
        return contexts.call(context, () -> transaction.execute(status -> work.get()));
    }

    /** Runs the work under the organization's context without starting a transaction (the work starts its own). */
    <T> T open(TenantId organization, UUID platformUser, Supplier<T> work) {
        return contexts.call(new TenantContext(organization, platformUser, null), work);
    }
}
