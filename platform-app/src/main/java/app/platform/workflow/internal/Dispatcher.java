package app.platform.workflow.internal;

import app.platform.sharedkernel.TenantId;
import app.platform.sharedkernel.audit.AuditOutcome;
import app.platform.sharedkernel.audit.AuditRecord;
import app.platform.sharedkernel.audit.AuditRecorder;
import app.platform.tenant.SystemScope;
import app.platform.tenant.TenantContext;
import app.platform.tenant.TenantContexts;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The dispatcher (ADR-0135): finds the queued executions and the due schedules of every organization, claims them, and
 * hands each to the runner that works inside that organization's own tenant context. It is the one place of the module
 * that enters the system scope {@code WORKFLOW_DISPATCH}, and it enters it only to claim, count and find organizations
 * with finished executions to clean up; nothing a record holds is read in it.
 *
 * <p>Every instance may run one (switch {@code PLATFORM_WORKFLOW_ENABLED}); they coordinate through the database only,
 * with claims that skip locked rows and leases that expire. A stopped instance finishes the execution it is running (up
 * to the shutdown wait) and leaves the rest queued; whatever it did not finish is claimed again after the lease.
 */
@Component
class Dispatcher implements SmartLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(Dispatcher.class);
    private static final Duration SHUTDOWN_WAIT = Duration.ofSeconds(25);
    private static final Duration MAINTENANCE_INTERVAL = Duration.ofSeconds(30);
    private static final int SCHEDULE_BATCH = 10;
    private static final int ORGANIZATIONS_PER_PASS = 50;

    private final DispatchStore store;
    private final ExecutionRunner runner;
    private final ScheduleRunner scheduleRunner;
    private final ExecutionStore executions;
    private final TenantContexts contexts;
    private final TransactionTemplate transaction;
    private final WorkflowProperties properties;
    private final AuditRecorder audit;
    private final MeterRegistry meters;
    private final AtomicLong queueDepth = new AtomicLong();
    private final AtomicLong deadLetters = new AtomicLong();

    private ScheduledExecutorService executor;
    private volatile boolean running;
    private long lastRetention;

    Dispatcher(DispatchStore store, ExecutionRunner runner, ScheduleRunner scheduleRunner, ExecutionStore executions,
            TenantContexts contexts, PlatformTransactionManager transactions, WorkflowProperties properties,
            AuditRecorder audit, MeterRegistry meters) {
        this.store = store;
        this.runner = runner;
        this.scheduleRunner = scheduleRunner;
        this.executions = executions;
        this.contexts = contexts;
        this.transaction = new TransactionTemplate(transactions);
        this.properties = properties;
        this.audit = audit;
        this.meters = meters;
        meters.gauge("platform.workflow.queue.depth", queueDepth);
        // Alert-ready: more than zero means a person has something to look at (ADR-0143).
        meters.gauge("platform.workflow.dead_letters", deadLetters);
    }

    // ---- one poll of each kind (public to the package so a test can drive them without waiting) ----

    /**
     * Claims one batch of queued executions and runs it.
     *
     * @return how many were claimed; a full batch means there may be more waiting
     */
    int pollExecutionsOnce() {
        List<DispatchStore.ClaimedExecution> claimed = asDispatcher(
                () -> store.claimExecutions(properties.worker().batchSize(), properties.worker().lease()));
        claimed.forEach(runner::run);
        return claimed.size();
    }

    /**
     * Claims the due schedules and fires them.
     *
     * @return how many were claimed
     */
    int pollSchedulesOnce() {
        List<DispatchStore.ClaimedSchedule> claimed = asDispatcher(
                () -> store.claimSchedules(SCHEDULE_BATCH, properties.scheduler().lease()));
        claimed.forEach(scheduleRunner::run);
        return claimed.size();
    }

    /**
     * Removes the finished executions older than the retention, and the dead letters older than theirs, organization by
     * organization.
     */
    int purgeOnce() {
        Duration retention = properties.retention().executions();
        Duration deadLetter = properties.retention().deadLetter();
        List<UUID> organizations = asDispatcher(() -> store.organizationsWithExpired(retention, deadLetter,
                ORGANIZATIONS_PER_PASS));
        int removed = 0;
        for (UUID organization : organizations) {
            removed += purgeOrganization(new TenantId(organization), retention, deadLetter);
        }
        return removed;
    }

    private int purgeOrganization(TenantId tenant, Duration retention, Duration deadLetter) {
        try {
            Integer count = contexts.call(TenantContext.of(tenant), () -> transaction.execute(status -> {
                int removed = executions.purgeFinished(retention, deadLetter, properties.retention().batchSize());
                if (removed > 0) {
                    audit.record(AuditRecord.of("retention.workflow.cleaned", AuditOutcome.SUCCESS).inTenant(tenant)
                            .with("removed", Integer.toString(removed)).with("source", "executions"));
                }
                return removed;
            }));
            return count == null ? 0 : count;
        } catch (RuntimeException failure) {
            LOG.warn("Cleaning the finished executions of an organization failed: {}", failure.getClass().getName());
            return 0;
        }
    }

    /** Refreshes the queue depth and dead-letter gauges. */
    void sampleQueue() {
        queueDepth.set(asDispatcher(store::queued));
        deadLetters.set(asDispatcher(store::deadLetters));
    }

    private <T> T asDispatcher(Supplier<T> work) {
        return contexts.callAsSystem(SystemScope.WORKFLOW_DISPATCH, () -> transaction.execute(status -> work.get()));
    }

    // ---- lifecycle ----

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        if (!properties.enabled()) {
            LOG.info("The workflow dispatcher is disabled on this instance");
            return;
        }
        int threads = properties.worker().threads() + 2;
        executor = Executors.newScheduledThreadPool(threads, runnable -> new Thread(runnable, "workflow-dispatch"));
        running = true;
        long poll = properties.worker().pollInterval().toMillis();
        for (int i = 0; i < properties.worker().threads(); i++) {
            executor.scheduleWithFixedDelay(this::safeExecutions, poll, poll, TimeUnit.MILLISECONDS);
        }
        long schedulerPoll = properties.scheduler().pollInterval().toMillis();
        executor.scheduleWithFixedDelay(this::safeSchedules, schedulerPoll, schedulerPoll, TimeUnit.MILLISECONDS);
        executor.scheduleWithFixedDelay(this::safeMaintenance, MAINTENANCE_INTERVAL.toMillis(),
                MAINTENANCE_INTERVAL.toMillis(), TimeUnit.MILLISECONDS);
        LOG.info("The workflow dispatcher started");
    }

    @Override
    public synchronized void stop() {
        running = false;
        if (executor == null) {
            return;
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(SHUTDOWN_WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
                // A claim that was never completed expires and the execution is claimed again by any instance.
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        executor = null;
        LOG.info("The workflow dispatcher stopped");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void safeExecutions() {
        try {
            int claimed;
            do {
                claimed = pollExecutionsOnce();
            } while (claimed >= properties.worker().batchSize() && running);
        } catch (RuntimeException failure) {
            // The loop must survive a database outage; the reason class is enough, driver text can quote data.
            LOG.warn("A workflow poll failed: {}", failure.getClass().getName());
        }
    }

    private void safeSchedules() {
        try {
            int claimed;
            do {
                claimed = pollSchedulesOnce();
            } while (claimed >= SCHEDULE_BATCH && running);
        } catch (RuntimeException failure) {
            LOG.warn("A schedule poll failed: {}", failure.getClass().getName());
        }
    }

    private void safeMaintenance() {
        try {
            sampleQueue();
            long now = System.nanoTime();
            if (lastRetention == 0 || now - lastRetention >= properties.retention().interval().toNanos()) {
                lastRetention = now == 0 ? 1 : now;
                int removed;
                do {
                    removed = purgeOnce();
                } while (removed > 0 && running);
            }
        } catch (RuntimeException failure) {
            LOG.warn("The workflow maintenance failed: {}", failure.getClass().getName());
        }
    }
}
