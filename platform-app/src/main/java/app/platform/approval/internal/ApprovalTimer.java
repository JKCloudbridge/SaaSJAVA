package app.platform.approval.internal;

import app.platform.approval.internal.ApprovalTimerStore.Claimed;
import app.platform.observability.ErrorReport;
import app.platform.observability.ErrorTracker;
import app.platform.sharedkernel.TenantId;
import app.platform.tenant.SystemScope;
import app.platform.tenant.TenantContext;
import app.platform.tenant.TenantContexts;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The approval timer (Sprint 25, ADR-0159): finds the active steps whose reminder, escalation or overdue mark has come
 * due, in every organization, and hands each to the engine inside that organization's own tenant context. It uses the
 * pattern of the workflow scheduler and does not start a second kind of scheduler: a due time kept in a row, claimed
 * with {@code FOR UPDATE SKIP LOCKED} and a lease, by any instance, with nothing held in memory that a restart could
 * lose.
 *
 * <p>It is the one place of the module that enters the system scope {@code approval_timers}, and it enters it only to
 * claim and count ({@link ApprovalTimerStore}); the work on a claimed step runs under the organization's own context,
 * in one transaction that also holds the lock a decision takes, so a reminder never races a decision. An instance that
 * dies mid-way leaves the claim to expire (the lease) and another takes the step over; the unique rows of
 * {@code approval_reminder} make a repeat harmless. A step that fails is reported (the type of the failure only) and
 * tried again after the lease.
 */
@Component
class ApprovalTimer implements SmartLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(ApprovalTimer.class);
    private static final long SHUTDOWN_WAIT_SECONDS = 20;

    private final ApprovalTimerStore store;
    private final ApprovalEngine engine;
    private final TenantContexts contexts;
    private final TransactionTemplate transaction;
    private final ErrorTracker tracker;
    private final MeterRegistry meters;
    private final ApprovalProperties.Timer settings;
    private final AtomicLong due = new AtomicLong();
    private final AtomicLong overdue = new AtomicLong();
    private ScheduledExecutorService executor;
    private volatile boolean running;

    ApprovalTimer(ApprovalTimerStore store, ApprovalEngine engine, TenantContexts contexts,
            TransactionTemplate transaction, ErrorTracker tracker, MeterRegistry meters,
            ApprovalProperties properties) {
        this.store = store;
        this.engine = engine;
        this.contexts = contexts;
        this.transaction = transaction;
        this.tracker = tracker;
        this.meters = meters;
        this.settings = properties.timer();
        Gauge.builder("platform.approval.timer.due", due, AtomicLong::get).register(meters);
        Gauge.builder("platform.approval.steps.overdue", overdue, AtomicLong::get).register(meters);
    }

    /**
     * One look: claims the steps that are due and handles each.
     *
     * @return how many were claimed; a full batch means there may be more waiting
     */
    int pollOnce() {
        List<Claimed> claimed = asTimer(() -> store.claim(settings.batchSize(), settings.lease()));
        for (Claimed step : claimed) {
            handle(step);
        }
        refreshGauges();
        return claimed.size();
    }

    private void handle(Claimed step) {
        try {
            contexts.run(TenantContext.of(new TenantId(step.tenantId())),
                    () -> transaction.executeWithoutResult(status -> engine.onTimer(step.stepId())));
            meters.counter("platform.approval.timer.looks", "result", "handled").increment();
        } catch (RuntimeException failure) {
            meters.counter("platform.approval.timer.looks", "result", "failed").increment();
            // The type only: the message of a library can quote data. The lease expires and the step is tried again.
            LOG.warn("The approval timer failed on a step: {}", failure.getClass().getName());
            tracker.track(ErrorReport.current(failure, "TIMER", "approval.timer"));
        }
    }

    /** The last count of overdue steps across organizations. */
    long overdueSteps() {
        return overdue.get();
    }

    /** The last count of timers that are due across organizations. */
    long timersDue() {
        return due.get();
    }

    private void refreshGauges() {
        try {
            due.set(asTimer(store::due));
            overdue.set(asTimer(store::overdue));
        } catch (RuntimeException failure) {
            LOG.warn("The approval timer could not count: {}", failure.getClass().getName());
        }
    }

    private <T> T asTimer(Supplier<T> work) {
        return contexts.callAsSystem(SystemScope.APPROVAL_TIMERS, () -> transaction.execute(status -> work.get()));
    }

    // ---- lifecycle ----

    @Override
    public synchronized void start() {
        if (running || !settings.enabled()) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> new Thread(runnable, "approval-timer"));
        running = true;
        long interval = settings.pollInterval().toMillis();
        executor.scheduleWithFixedDelay(this::safePoll, interval, interval, TimeUnit.MILLISECONDS);
        LOG.info("The approval timer started");
    }

    @Override
    public synchronized void stop() {
        running = false;
        if (executor == null) {
            return;
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS)) {
                // A claim that was never completed expires and the step is claimed again by any instance.
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        executor = null;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void safePoll() {
        try {
            int claimed;
            do {
                claimed = pollOnce();
            } while (claimed >= settings.batchSize() && running);
        } catch (RuntimeException failure) {
            LOG.warn("An approval timer poll failed: {}", failure.getClass().getName());
        }
    }
}
