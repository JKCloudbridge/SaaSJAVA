package app.platform.notification.internal;

import app.platform.sharedkernel.audit.AuditOutcome;
import app.platform.sharedkernel.audit.AuditRecord;
import app.platform.sharedkernel.audit.AuditRecorder;
import app.platform.tenant.Tenant;
import app.platform.tenant.TenantContext;
import app.platform.tenant.TenantContexts;
import app.platform.tenant.Tenants;
import io.micrometer.core.instrument.MeterRegistry;
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
 * Removes old in-app notices (Sprint 25, ADR-0154): read ones after {@code readKeep} (30 days), unread ones after the
 * longer {@code unreadKeep} (180 days), and nothing younger than the floor (7 days) whatever the other two say
 * ({@link NoticeProperties.Retention}). Runs on every instance that has it switched on; two instances doing the same
 * work at once is harmless (the second delete matches nothing of what the first already removed).
 *
 * <p>It works organization by organization, in that organization's own tenant context and its own transaction, as the
 * recycle bin clean-up does: row level security stays on, no system scope is needed, and an organization that fails
 * does not hold up the others. One audit record per organization and pass counts what was removed. Nothing of the
 * notices is logged.
 */
@Component
class NoticeRetention implements SmartLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(NoticeRetention.class);
    private static final int TENANT_PAGE = 200;

    private final Tenants tenants;
    private final TenantContexts contexts;
    private final NoticeStore store;
    private final AuditRecorder audit;
    private final MeterRegistry meters;
    private final TransactionTemplate transaction;
    private final NoticeProperties.Retention settings;
    private ScheduledExecutorService executor;

    NoticeRetention(Tenants tenants, TenantContexts contexts, NoticeStore store, AuditRecorder audit,
            MeterRegistry meters, PlatformTransactionManager transactions, NoticeProperties properties) {
        this.tenants = tenants;
        this.contexts = contexts;
        this.store = store;
        this.audit = audit;
        this.meters = meters;
        this.transaction = new TransactionTemplate(transactions);
        this.settings = properties.retention();
    }

    /**
     * One pass over every organization. Public to the package so a test can run it without waiting.
     *
     * @return how many notices were removed
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
            meters.counter("platform.notification.notices.removed").increment(removed);
        }
        return removed;
    }

    private int purgeOne(Tenant tenant) {
        try {
            Integer count = contexts.call(TenantContext.of(tenant.id()),
                    () -> transaction.execute(status -> removeFor(tenant)));
            return count == null ? 0 : count;
        } catch (RuntimeException e) {
            LOG.warn("Removing old notices failed ({})", e.getClass().getSimpleName());
            return 0;
        }
    }

    /** Runs inside the organization's own context and transaction. */
    private int removeFor(Tenant tenant) {
        int total = 0;
        int removed;
        do {
            removed = store.purge(settings.readCutoff(), settings.unreadCutoff(), settings.batchSize());
            total += removed;
        } while (removed >= settings.batchSize());
        if (total > 0) {
            audit.record(AuditRecord.of("retention.notifications.cleaned", AuditOutcome.SUCCESS)
                    .inTenant(tenant.id()).with("removed", Integer.toString(total)));
        }
        return total;
    }

    @Override
    public synchronized void start() {
        if (!settings.enabled() || executor != null) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> new Thread(runnable, "notice-retention"));
        executor.scheduleWithFixedDelay(() -> {
            try {
                runOnce();
            } catch (RuntimeException e) {
                LOG.warn("The notice clean-up failed ({})", e.getClass().getSimpleName());
            }
        }, settings.interval().toMillis(), settings.interval().toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized void stop() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    @Override
    public synchronized boolean isRunning() {
        return executor != null;
    }
}
