package app.platform.security.internal;

import app.platform.tenant.TenantContexts;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs work in a transaction for the organization the thread works for now (the tenant context must be open before the
 * transaction begins, ADR-0015). When a transaction runs it is joined, so a change of access belongs to the transaction
 * of the action that caused it (an acceptance, a deactivation) and commits or rolls back with it.
 */
@Component
class AccessWork {

    private final TenantContexts contexts;
    private final TransactionTemplate transaction;

    AccessWork(TenantContexts contexts, TransactionTemplate transaction) {
        this.contexts = contexts;
        this.transaction = transaction;
    }

    <T> T run(Supplier<T> work) {
        contexts.require();
        return transaction.execute(status -> work.get());
    }

    void runVoid(Runnable work) {
        run(() -> {
            work.run();
            return null;
        });
    }
}
