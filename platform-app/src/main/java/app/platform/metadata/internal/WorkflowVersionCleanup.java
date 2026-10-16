package app.platform.metadata.internal;

import app.platform.tenant.Tenant;
import app.platform.tenant.TenantContext;
import app.platform.tenant.TenantContexts;
import app.platform.tenant.Tenants;
import java.time.Duration;
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
 * Removes old definitions of workflows that nothing names any more (ADR-0136): not the current definition of a workflow
 * and not one an execution still points at. The database decides what is named (a foreign key), so this module never
 * reads the tables of executions. A definition is kept for a week after it was replaced, so a person who just changed a
 * workflow can still look at what it was in the history of an execution that ran under it.
 *
 * <p>Works organization by organization, in that organization's own tenant context and transaction, like the clean-up
 * of the recycle bin: row level security stays on, no system scope is needed, and an organization that fails does not
 * hold up the others. Two instances doing the same work at once is harmless.
 */
@Component
class WorkflowVersionCleanup implements SmartLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(WorkflowVersionCleanup.class);
    private static final Duration INTERVAL = Duration.ofHours(6);
    private static final Duration GRACE = Duration.ofDays(7);
    private static final int BATCH = 200;
    private static final int TENANT_PAGE = 200;

    private final Tenants tenants;
    private final TenantContexts contexts;
    private final WorkflowStore store;
    private final TransactionTemplate transaction;
    private ScheduledExecutorService executor;

    WorkflowVersionCleanup(Tenants tenants, TenantContexts contexts, WorkflowStore store,
            PlatformTransactionManager transactions) {
        this.tenants = tenants;
        this.contexts = contexts;
        this.store = store;
        this.transaction = new TransactionTemplate(transactions);
    }

    /**
     * One pass over every organization. Public to the package so a test can run it without waiting.
     *
     * @return how many definitions were removed
     */
    int runOnce() {
        int removed = 0;
        String after = null;
        while (true) {
            List<Tenant> page = tenants.list(after, null, TENANT_PAGE);
            for (Tenant tenant : page) {
                removed += cleanOne(tenant);
            }
            if (page.size() < TENANT_PAGE) {
                break;
            }
            after = page.get(page.size() - 1).slug().value();
        }
        return removed;
    }

    private int cleanOne(Tenant tenant) {
        try {
            Integer count = contexts.call(TenantContext.of(tenant.id()),
                    () -> transaction.execute(status -> store.cleanVersions(GRACE, BATCH)));
            return count == null ? 0 : count;
        } catch (RuntimeException e) {
            LOG.warn("Cleaning old workflow definitions failed ({})", e.getClass().getSimpleName());
            return 0;
        }
    }

    @Override
    public void start() {
        if (executor != null) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> new Thread(runnable,
                "workflow-version-cleanup"));
        executor.scheduleWithFixedDelay(() -> {
            try {
                runOnce();
            } catch (RuntimeException e) {
                LOG.warn("The clean-up of workflow definitions failed ({})", e.getClass().getSimpleName());
            }
        }, INTERVAL.toMillis(), INTERVAL.toMillis(), TimeUnit.MILLISECONDS);
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
