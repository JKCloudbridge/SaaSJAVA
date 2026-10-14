package app.platform.data.internal;

import app.platform.sharedkernel.ActorId;
import app.platform.sharedkernel.TenantId;
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
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Keeps the object store and the file rows in step (Sprint 17, ADR-0121): removes the bytes of files whose rows are
 * gone and expires uploads that never finished.
 *
 * <p>Every way a file row disappears (a person removes the file, the record is removed for good, an upload fails)
 * writes the key of its bytes to a queue in the same transaction (a trigger, V046). This job drains the queue: it
 * removes the bytes first and the queue entry after, so a crash between the two costs one repeated removal and never a
 * lost key. An upload that was never finished (the instance died while streaming) has a pending row that is older than
 * the allowed time; the job removes the row, which queues its bytes. Every byte in the store therefore has a row or a
 * queue entry from before it exists, and the queue is bounded per pass and resumes where it stopped: this is the
 * "periodic check for orphans" of the sprint, and it needs no listing of the store.
 *
 * <p>It works organization by organization in that organization's own context and transactions, like the clean-up of
 * the recycle bin; two instances doing the same work at once is harmless (a removal of bytes is repeatable). When the
 * store does not answer, the pass for that organization stops and the entries stay. Nothing of a file is logged.
 */
@Component
class FileCleanup implements SmartLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(FileCleanup.class);
    private static final int TENANT_PAGE = 200;
    private static final ActorId SYSTEM = ActorId.SYSTEM;

    private final Tenants tenants;
    private final TenantContexts contexts;
    private final FileStore files;
    private final ObjectStore objects;
    private final RecordChanges changes;
    private final TransactionTemplate transaction;
    private final FileProperties settings;
    private ScheduledExecutorService executor;

    FileCleanup(Tenants tenants, TenantContexts contexts, FileStore files, ObjectStore objects, RecordChanges changes,
            TransactionTemplate transaction, FileProperties properties) {
        this.tenants = tenants;
        this.contexts = contexts;
        this.files = files;
        this.objects = objects;
        this.changes = changes;
        this.transaction = transaction;
        this.settings = properties;
    }

    /**
     * One pass over every organization. Public to the package so a test can run it without waiting.
     *
     * @return how many keys of bytes were removed from the store
     */
    int runOnce() {
        if (!objects.configured()) {
            return 0;
        }
        int removed = 0;
        String after = null;
        while (true) {
            List<Tenant> page = tenants.list(after, null, TENANT_PAGE);
            for (Tenant tenant : page) {
                removed += workOn(tenant.id());
            }
            if (page.size() < TENANT_PAGE) {
                break;
            }
            after = page.get(page.size() - 1).slug().value();
        }
        return removed;
    }

    /** The work of one organization. Public to the package so a test can run it for the organization it made. */
    int workOn(TenantId tenant) {
        if (!objects.configured()) {
            return 0;
        }
        try {
            Integer count = contexts.call(TenantContext.of(tenant), () -> cleanInContext(tenant));
            return count == null ? 0 : count;
        } catch (RuntimeException e) {
            LOG.warn("The file clean-up of an organization failed ({})", e.getClass().getSimpleName());
            return 0;
        }
    }

    /** Runs inside the organization's own context. */
    private int cleanInContext(TenantId tenant) {
        int batch = settings.cleanup().batchSize();
        Integer expired = transaction.execute(status -> files.deleteExpiredPending(settings.pendingTtl(), batch));
        List<String> keys = transaction.execute(status -> files.queuedKeys(batch));
        int removed = 0;
        for (String key : keys == null ? List.<String>of() : keys) {
            try {
                objects.delete(key);
            } catch (ObjectStoreException e) {
                transaction.executeWithoutResult(status -> files.attempted(key, SYSTEM.value()));
                LOG.warn("The object store did not remove bytes now; the pass for this organization stops");
                break;
            }
            transaction.executeWithoutResult(status -> files.dequeue(key));
            removed++;
        }
        int expiredCount = expired == null ? 0 : expired;
        if (removed > 0 || expiredCount > 0) {
            int bytesRemoved = removed;
            transaction.executeWithoutResult(status -> changes.fileCleanupPass(tenant, bytesRemoved,
                    expiredCount));
        }
        return removed;
    }

    @Override
    public void start() {
        if (!settings.cleanup().enabled() || executor != null) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> new Thread(runnable, "file-cleanup"));
        long period = settings.cleanup().interval().toMillis();
        executor.scheduleWithFixedDelay(() -> {
            try {
                runOnce();
            } catch (RuntimeException e) {
                LOG.warn("The file clean-up failed ({})", e.getClass().getSimpleName());
            }
        }, period, period, TimeUnit.MILLISECONDS);
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
