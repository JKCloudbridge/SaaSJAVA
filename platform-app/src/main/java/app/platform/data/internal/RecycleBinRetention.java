package app.platform.data.internal;

import app.platform.data.StorageStrategy;
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
 * Empties the recycle bin: removes, for good, records that have been in it longer than the retention period ({@link
 * RecordProperties.Retention}, ADR-0086). Runs on every instance that has it switched on; two instances doing the same
 * work at once is harmless (the second delete matches nothing of what the first one already removed).
 *
 * <p>It works organization by organization, in that organization's own tenant context and its own transaction: row
 * level security stays on, no system scope is needed, and an organization that fails does not hold up the others. Every
 * removed record writes the event of a permanent delete, and one audit record per organization and pass counts them.
 * Nothing of the records themselves is logged.
 */
@Component
class RecycleBinRetention implements SmartLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(RecycleBinRetention.class);
    private static final int TENANT_PAGE = 200;

    private final Tenants tenants;
    private final TenantContexts contexts;
    private final StorageStrategy storage;
    private final RecordChanges changes;
    private final TransactionTemplate transaction;
    private final RecordProperties.Retention settings;
    private ScheduledExecutorService executor;

    RecycleBinRetention(Tenants tenants, TenantContexts contexts, StorageStrategy storage, RecordChanges changes,
            PlatformTransactionManager transactions, RecordProperties properties) {
        this.tenants = tenants;
        this.contexts = contexts;
        this.storage = storage;
        this.changes = changes;
        this.transaction = new TransactionTemplate(transactions);
        this.settings = properties.retention();
    }

    /**
     * One pass over every organization. Public to the package so a test can run it without waiting.
     *
     * @return how many records were removed for good
     */
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
            LOG.info("Removed {} records from recycle bins after {}", removed, settings.recycleBin());
        }
        return removed;
    }

    private int purgeOne(Tenant tenant) {
        try {
            return contexts.call(TenantContext.of(tenant.id()), () -> {
                Integer count = transaction.execute(status -> {
                    List<StorageStrategy.Purged> purged = storage.purgeDeletedOlderThan(settings.recycleBin(),
                            settings.batchSize());
                    purged.forEach(one -> changes.purgedByRetention(one.objectApiName(), one.id()));
                    if (!purged.isEmpty()) {
                        changes.retentionPass(tenant.id(), purged.size());
                    }
                    return purged.size();
                });
                return count == null ? 0 : count;
            });
        } catch (RuntimeException e) {
            LOG.warn("Emptying a recycle bin failed ({})", e.getClass().getSimpleName());
            return 0;
        }
    }

    @Override
    public void start() {
        if (!settings.enabled() || executor != null) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> new Thread(runnable, "recycle-bin-cleanup"));
        executor.scheduleWithFixedDelay(() -> {
            try {
                runOnce();
            } catch (RuntimeException e) {
                LOG.warn("The recycle bin clean-up failed ({})", e.getClass().getSimpleName());
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
