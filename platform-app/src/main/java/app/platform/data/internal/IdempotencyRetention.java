package app.platform.data.internal;

import app.platform.tenant.Tenant;
import app.platform.tenant.TenantContext;
import app.platform.tenant.TenantContexts;
import app.platform.tenant.Tenants;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Removes the outcomes of keyed writes that have expired (Sprint 30, ADR-0246). An expired outcome is also removed
 * when its key is used again, so this job is housekeeping, not correctness. Like the other clean-ups of the record
 * store it works organization by organization in that organization's own context and transaction: row level security
 * stays on, no system scope is needed, and an organization that fails does not hold up the others. Two instances doing
 * the same work at once is harmless. Nothing of any row is logged.
 */
@Component
class IdempotencyRetention implements SmartLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(IdempotencyRetention.class);
    private static final int TENANT_PAGE = 200;

    private final Tenants tenants;
    private final TenantContexts contexts;
    private final IdempotencyStore store;
    private final TransactionTemplate transaction;
    private final IdempotencyProperties settings;
    private ScheduledExecutorService executor;

    IdempotencyRetention(Tenants tenants, TenantContexts contexts, IdempotencyStore store,
            PlatformTransactionManager transactions, IdempotencyProperties settings) {
        this.tenants = tenants;
        this.contexts = contexts;
        this.store = store;
        this.transaction = new TransactionTemplate(transactions);
        this.settings = settings;
    }

    /** One pass over every organization; package-private so a test can run it without waiting. */
    int runOnce() {
        int removed = 0;
        String after = null;
        while (true) {
            List<Tenant> page = tenants.list(after, null, TENANT_PAGE);
            for (Tenant tenant : page) {
                removed += purgeOne(tenant);
            }
            if (page.size() < TENANT_PAGE) {
                break;
            }
            after = page.get(page.size() - 1).slug().value();
        }
        if (removed > 0) {
            LOG.info("Removed {} expired outcomes of keyed writes", removed);
        }
        return removed;
    }

    private int purgeOne(Tenant tenant) {
        try {
            Integer count = contexts.call(TenantContext.of(tenant.id()),
                    () -> transaction.execute(status -> store.purgeExpired(settings.batchSize())));
            return count == null ? 0 : count;
        } catch (RuntimeException e) {
            LOG.warn("Removing expired outcomes of keyed writes failed ({})", e.getClass().getSimpleName());
            return 0;
        }
    }

    @Override
    public void start() {
        if (!settings.enabled() || executor != null) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(
                runnable -> new Thread(runnable, "idempotency-cleanup"));
        executor.scheduleWithFixedDelay(() -> {
            try {
                runOnce();
            } catch (RuntimeException e) {
                LOG.warn("The clean-up of keyed writes failed ({})", e.getClass().getSimpleName());
            }
        }, settings.interval().toMillis(), settings.interval().toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public void stop() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    @Override
    public boolean isRunning() {
        return executor != null;
    }
}
