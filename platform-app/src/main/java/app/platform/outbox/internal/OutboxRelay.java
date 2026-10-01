package app.platform.outbox.internal;

import app.platform.observability.ErrorReport;
import app.platform.observability.ErrorTracker;
import app.platform.outbox.internal.OutboxStore.ClaimedEvent;
import app.platform.sharedkernel.TenantId;
import app.platform.sharedkernel.events.EventEnvelope;
import app.platform.sharedkernel.events.EventHandler;
import app.platform.sharedkernel.logging.LogContext;
import app.platform.tenant.SystemScope;
import app.platform.tenant.TenantContext;
import app.platform.tenant.TenantContexts;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The polling publisher: claims due events, hands them to their handlers after the commit that wrote them, and records
 * the outcome (decision D3, ADR-0016). Every instance of the application may run one; they coordinate through the
 * database only.
 *
 * <p><strong>Guarantees.</strong>
 * <ul>
 *   <li>An event exists only if its change committed (the publisher writes it in the same transaction).</li>
 *   <li>Delivery is at least once: a claim that is never completed (crash, stall) expires and the event is claimed
 *       again. Handling is effectively once, because each handler's changes and its idempotency marker commit in one
 *       transaction ({@link EventMarkers}).</li>
 *   <li>A failing event is retried with a growing delay and, after {@code maxAttempts} handlings, becomes a dead
 *       letter that stays in the table for a person to look at. An event whose handling crashes the process is
 *       dead-lettered too, because the claim counts the attempt before the handlers run.</li>
 *   <li>Each handler runs with the tenant context of the event, in its own transaction.</li>
 * </ul>
 *
 * <p>A poll handles its batch one event after the other; the lease must be longer than the whole batch takes. If it
 * is not, another instance may take the remaining events over, which is safe (the markers) but wasteful.
 */
@Component
class OutboxRelay implements SmartLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(OutboxRelay.class);
    private static final int MAX_ERROR_TYPE = 200;
    private static final Duration PURGE_INTERVAL = Duration.ofMinutes(10);
    private static final Duration SHUTDOWN_WAIT = Duration.ofSeconds(25);

    private final OutboxStore store;
    private final EventMarkers markers;
    private final HandlerRegistry registry;
    private final TenantContexts contexts;
    private final TransactionTemplate transaction;
    private final OutboxProperties properties;
    private final ErrorTracker tracker;
    private final MeterRegistry meters;
    private final Backoff backoff;

    private ScheduledExecutorService executor;
    private volatile boolean running;

    OutboxRelay(OutboxStore store, EventMarkers markers, HandlerRegistry registry, TenantContexts contexts,
            PlatformTransactionManager transactionManager, OutboxProperties properties, ErrorTracker tracker,
            MeterRegistry meters) {
        this.store = store;
        this.markers = markers;
        this.registry = registry;
        this.contexts = contexts;
        this.transaction = new TransactionTemplate(transactionManager);
        this.properties = properties;
        this.tracker = tracker;
        this.meters = meters;
        this.backoff = new Backoff(properties.backoffInitial(), properties.backoffMax(), RandomGenerator.getDefault());
    }

    // ---- one poll ----

    /**
     * Claims one batch and handles it.
     *
     * @return how many events were claimed; a full batch means there may be more waiting
     */
    int pollOnce() {
        List<ClaimedEvent> claimed = asRelay(() -> store.claim(properties.batchSize(), properties.lease()));
        for (ClaimedEvent event : claimed) {
            process(event);
        }
        return claimed.size();
    }

    /** Removes expired delivered events and idempotency markers. */
    void purge() {
        int events;
        int expiredMarkers;
        do {
            events = asRelay(() -> store.purgeDelivered(properties.deliveredRetention()));
        } while (events > 0 && running);
        do {
            expiredMarkers = asRelay(() -> store.purgeMarkers(properties.markerRetention()));
        } while (expiredMarkers > 0 && running);
    }

    private void process(ClaimedEvent event) {
        if (event.attempt() > properties.maxAttempts()) {
            // Earlier handlings never reported back: the process died or stalled each time.
            deadLetter(event, "AttemptsExhausted", new IllegalStateException("Event attempts exhausted"));
            return;
        }
        EventEnvelope envelope = new EventEnvelope(event.id(), new TenantId(event.tenantId()), event.userId(),
                event.membershipId(), event.type(), event.payload(), event.occurredAt(), event.attempt());
        List<EventHandler> handlers = registry.handlersFor(event.type());
        try {
            contexts.run(context(event), () -> handlers.forEach(handler -> deliver(handler, envelope)));
        } catch (RuntimeException failure) {
            fail(event, failure);
            return;
        }
        if (asRelay(() -> store.markDelivered(event))) {
            meters.counter("platform.outbox.events", "result", "delivered").increment();
            LOG.debug("Event {} of type {} delivered at attempt {}", event.id(), event.type(), event.attempt());
        } else {
            LOG.info("Event {} was taken over by another instance; its outcome is theirs", event.id());
        }
    }

    /** One handler, one transaction: the marker and the handler's own changes commit together or not at all. */
    private void deliver(EventHandler handler, EventEnvelope envelope) {
        transaction.executeWithoutResult(status -> {
            if (markers.firstTime(handler.consumerName(), envelope.eventId())) {
                handler.handle(envelope);
            } else {
                LOG.debug("Consumer {} already handled event {}", handler.consumerName(), envelope.eventId());
            }
        });
    }

    private void fail(ClaimedEvent event, RuntimeException failure) {
        String errorType = errorType(failure);
        if (event.attempt() >= properties.maxAttempts()) {
            deadLetter(event, errorType, failure);
            return;
        }
        Duration delay = backoff.delayAfter(event.attempt());
        boolean recorded = asRelay(() -> store.scheduleRetry(event, delay, errorType));
        meters.counter("platform.outbox.events", "result", "retried").increment();
        inTenant(event, () -> LOG.atWarn()
                .addKeyValue("event_id", event.id())
                .addKeyValue("event_type", event.type())
                .addKeyValue("attempt", event.attempt())
                .addKeyValue("error_type", errorType)
                .addKeyValue("retry_in_ms", delay.toMillis())
                .addKeyValue("recorded", recorded)
                .log("Event handling failed, will retry"));
    }

    private void deadLetter(ClaimedEvent event, String errorType, RuntimeException failure) {
        boolean recorded = asRelay(() -> store.markDead(event, errorType));
        meters.counter("platform.outbox.events", "result", "dead").increment();
        inTenant(event, () -> {
            LOG.atError()
                    .addKeyValue("event_id", event.id())
                    .addKeyValue("event_type", event.type())
                    .addKeyValue("attempt", event.attempt())
                    .addKeyValue("error_type", errorType)
                    .addKeyValue("recorded", recorded)
                    .log("Event set aside as a dead letter");
            tracker.track(new ErrorReport(failure, ErrorReport.UNKNOWN_REQUEST, LogContext.traceId(),
                    LogContext.tenantId(), "EVENT", event.type()));
        });
    }

    // ---- scopes ----

    private <T> T asRelay(Supplier<T> work) {
        return contexts.callAsSystem(SystemScope.OUTBOX_RELAY, () -> transaction.execute(status -> work.get()));
    }

    /** Runs the work with the event's tenant open, so the log line carries the tenant ID from the logging context. */
    private void inTenant(ClaimedEvent event, Runnable work) {
        contexts.run(context(event), work);
    }

    private static TenantContext context(ClaimedEvent event) {
        return new TenantContext(new TenantId(event.tenantId()), event.userId(), event.membershipId());
    }

    private static String errorType(RuntimeException failure) {
        String name = failure.getClass().getSimpleName();
        return name.length() > MAX_ERROR_TYPE ? name.substring(0, MAX_ERROR_TYPE) : name;
    }

    // ---- lifecycle ----

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        if (!properties.enabled()) {
            LOG.info("The outbox relay is disabled on this instance");
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> new Thread(runnable, "outbox-relay"));
        running = true;
        long interval = properties.pollInterval().toMillis();
        executor.scheduleWithFixedDelay(this::safePoll, interval, interval, TimeUnit.MILLISECONDS);
        executor.scheduleWithFixedDelay(this::safePurge, PURGE_INTERVAL.toMillis(), PURGE_INTERVAL.toMillis(),
                TimeUnit.MILLISECONDS);
        LOG.info("The outbox relay started");
    }

    @Override
    public synchronized void stop() {
        if (executor == null) {
            running = false;
            return;
        }
        running = false;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(SHUTDOWN_WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
                // A claim that was never completed expires and the event is claimed again by any instance.
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        executor = null;
        LOG.info("The outbox relay stopped");
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
            } while (claimed >= properties.batchSize() && running);
        } catch (RuntimeException failure) {
            // The scheduler must survive a database outage; the reason class is enough, driver text can quote data.
            LOG.warn("An outbox poll failed: {}", failure.getClass().getName());
        }
    }

    private void safePurge() {
        try {
            purge();
        } catch (RuntimeException failure) {
            LOG.warn("The outbox clean-up failed: {}", failure.getClass().getName());
        }
    }
}
