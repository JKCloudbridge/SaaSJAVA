package app.platform.sheets.internal;

import app.platform.tenant.Tenant;
import app.platform.tenant.TenantContext;
import app.platform.tenant.TenantContexts;
import app.platform.tenant.Tenants;
import java.time.Instant;
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
 * Moves the cells of old approved fill-ins to the object store (FF2, ADR-0193). Runs on every instance that has it
 * switched on; two instances at once are safe because each fill-in is moved under the lock its saves take and the
 * second finds it done ({@link FillArchive}).
 *
 * <p>It works organization by organization, in that organization's own tenant context, as the platform does for the
 * recycle bin: row level security stays on and no system scope is needed. Each fill-in is its own transaction, so a
 * failure of one (the store is down, a hash does not match) rolls back that fill-in only and leaves nothing half done;
 * the pass goes on to the next and the failed one is tried again at the next pass. A pass moves at most the configured
 * batch for each organization, so a long backlog is worked off over several passes without holding other organizations
 * up. Nothing of a fill-in is logged: a count and the name of an exception class at most.
 */
@Component
class FillArchiveJob implements SmartLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(FillArchiveJob.class);
    private static final int TENANT_PAGE = 200;

    private final Tenants tenants;
    private final TenantContexts contexts;
    private final SheetFillStore store;
    private final FillArchive archive;
    private final FillChanges changes;
    private final TransactionTemplate transaction;
    private final FillArchiveProperties settings;
    private ScheduledExecutorService executor;

    FillArchiveJob(Tenants tenants, TenantContexts contexts, SheetFillStore store, FillArchive archive,
            FillChanges changes, PlatformTransactionManager transactions, FillArchiveProperties settings) {
        this.tenants = tenants;
        this.contexts = contexts;
        this.store = store;
        this.archive = archive;
        this.changes = changes;
        this.transaction = new TransactionTemplate(transactions);
        this.settings = settings;
    }

    /**
     * One pass over every organization. Package-private so a test can run it without waiting.
     *
     * @return how many fill-ins were moved
     */
    int runOnce() {
        if (!archive.available()) {
            return 0;
        }
        Instant cutoff = Instant.now().minus(settings.age());
        int moved = 0;
        String after = null;
        while (true) {
            List<Tenant> page = tenants.list(after, null, TENANT_PAGE);
            for (Tenant tenant : page) {
                moved += passFor(tenant, cutoff);
            }
            if (page.size() < TENANT_PAGE) {
                break;
            }
            after = page.get(page.size() - 1).slug().value();
        }
        if (moved > 0) {
            LOG.info("Moved the cells of {} old fill-ins to the archive", moved);
        }
        return moved;
    }

    private int passFor(Tenant tenant, Instant cutoff) {
        try {
            Integer count = contexts.call(TenantContext.of(tenant.id()), () -> archiveBatch(cutoff));
            return count == null ? 0 : count;
        } catch (RuntimeException e) {
            changes.archiveProblem("pass");
            LOG.warn("Archiving old fill-ins of an organization failed ({})", e.getClass().getSimpleName());
            return 0;
        }
    }

    /** Runs inside the organization's own tenant context. */
    private int archiveBatch(Instant cutoff) {
        List<SheetFillStore.Ref> due = transaction.execute(status -> store.archivable(cutoff, settings.batchSize()));
        if (due == null) {
            return 0;
        }
        int moved = 0;
        for (SheetFillStore.Ref ref : due) {
            try {
                Boolean done = transaction.execute(status -> archive.archive(ref.object(), ref.id()));
                if (Boolean.TRUE.equals(done)) {
                    moved++;
                }
            } catch (RuntimeException e) {
                // Rolled back with its transaction: nothing of this fill-in moved. The next pass tries again.
                changes.archiveProblem("failed");
                LOG.warn("A fill-in could not be archived now ({})", e.getClass().getSimpleName());
            }
        }
        return moved;
    }

    @Override
    public void start() {
        if (!settings.enabled() || executor != null) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> new Thread(runnable, "fill-archive"));
        executor.scheduleWithFixedDelay(() -> {
            try {
                runOnce();
            } catch (RuntimeException e) {
                LOG.warn("The fill-in archive pass failed ({})", e.getClass().getSimpleName());
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
