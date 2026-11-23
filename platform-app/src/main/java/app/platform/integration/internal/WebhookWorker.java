package app.platform.integration.internal;

import app.platform.integration.internal.EgressClient.Answer;
import app.platform.integration.internal.WebhookAttemptStore.Outcome;
import app.platform.integration.internal.WebhookDeliveryStore.Claimed;
import app.platform.integration.internal.WebhookDeliveryStore.Delivery;
import app.platform.integration.internal.WebhookSubscriptionStore.Subscription;
import app.platform.licensing.Entitlements;
import app.platform.observability.ErrorReport;
import app.platform.observability.ErrorTracker;
import app.platform.security.MemberAccess;
import app.platform.sharedkernel.ActorId;
import app.platform.sharedkernel.TenantId;
import app.platform.sharedkernel.audit.AuditOutcome;
import app.platform.sharedkernel.audit.AuditRecord;
import app.platform.sharedkernel.audit.AuditRecorder;
import app.platform.sharedkernel.notice.NoticeKind;
import app.platform.sharedkernel.notice.NoticeRequest;
import app.platform.sharedkernel.notice.Notices;
import app.platform.tenant.SystemScope;
import app.platform.tenant.TenantContext;
import app.platform.tenant.TenantContexts;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sends the queued webhook deliveries (Sprint 32, ADR-0278, ADR-0279). The handler of an event only writes a delivery
 * row; this worker, on any instance, claims what is due with a lease in the system scope {@code webhook_dispatch} (the
 * claim is fair between organizations: one with thousands of events cannot fill a whole claim), decides whether the
 * delivery may be made now, makes one attempt through {@link WebhookCaller} with no transaction held, and records how
 * it
 * ended. A failure that may pass is tried again after a wait kept in the row; one that will not, or one that used every
 * attempt, ends as a dead letter that an administrator may replay; an address that fails without one success for the
 * period the settings state is switched off by the platform and the administrators are told. Nothing a restart cuts off
 * is lost: a claimed delivery comes back when its lease ends.
 *
 * <p><strong>Three steps per attempt.</strong> A transaction decides (is the subscription on, may its member read the
 * record <em>now</em>, may the address be called now) and takes the right to make the attempt; then the call, with
 * nothing open; last a transaction records the end. Nothing is sent inside a transaction, and nothing is sent for a
 * record the acting member cannot read.
 *
 * <p>It is the one class of the webhooks that enters the system scope {@code webhook_dispatch}, which it enters only to
 * claim and count. Nothing here logs, audits or puts in a metric tag an address, a header, a signature, a record, an
 * object or a person; a failure of the framework is reported to the error tracking by its class name only.
 */
@Component
class WebhookWorker implements SmartLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(WebhookWorker.class);
    private static final UUID SYSTEM = ActorId.SYSTEM.value();
    private static final int DEFER_BATCH = 2000;
    private static final long SHUTDOWN_WAIT_SECONDS = 20;
    private static final String FEATURE = "integrations";

    /** What the first transaction decides: nothing more to do, or a request ready to make. */
    private record Begun(Ready ready) {

        static Begun nothing() {
            return new Begun(null);
        }
    }

    private record Ready(Subscription subscription, Delivery delivery, EndpointRules.Endpoint endpoint,
            List<Integer> secrets, Instant startedAt) {
    }

    /** How an attempt ended, before it is written down. */
    private record Ended(RetryPolicy.Verdict verdict, Integer status, String statusClass, Integer retryAfter) {
    }

    private final TransactionTemplate transaction;
    private final TenantContexts contexts;
    private final WebhookSubscriptionStore subscriptions;
    private final WebhookDeliveryStore deliveries;
    private final WebhookAttemptStore attempts;
    private final WebhookGate gate;
    private final WebhookVisibility visibility;
    private final WebhookCaller caller;
    private final WebhookRetention retention;
    private final EndpointRules.Policy policy;
    private final WebhookProperties settings;
    private final Entitlements entitlements;
    private final MemberAccess access;
    private final Notices notices;
    private final AuditRecorder audit;
    private final ErrorTracker tracker;
    private final MeterRegistry meters;
    private final Clock clock;
    private final Semaphore slots;
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicLong depth = new AtomicLong();
    private final AtomicLong age = new AtomicLong();
    private final AtomicLong dead = new AtomicLong();
    private volatile ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private ScheduledExecutorService scheduler;
    private volatile boolean running;

    WebhookWorker(PlatformTransactionManager transactions, TenantContexts contexts,
            WebhookSubscriptionStore subscriptions, WebhookDeliveryStore deliveries, WebhookAttemptStore attempts,
            WebhookGate gate, WebhookVisibility visibility, WebhookCaller caller, WebhookRetention retention,
            EndpointRules.Policy policy, WebhookProperties settings,
            Entitlements entitlements, MemberAccess access, Notices notices, AuditRecorder audit,
            ErrorTracker tracker, MeterRegistry meters, Clock clock) {
        this.transaction = new TransactionTemplate(transactions);
        this.contexts = contexts;
        this.subscriptions = subscriptions;
        this.deliveries = deliveries;
        this.attempts = attempts;
        this.gate = gate;
        this.visibility = visibility;
        this.caller = caller;
        this.retention = retention;
        this.policy = policy;
        this.settings = settings;
        this.entitlements = entitlements;
        this.access = access;
        this.notices = notices;
        this.audit = audit;
        this.tracker = tracker;
        this.meters = meters;
        this.clock = clock;
        this.slots = new Semaphore(settings.worker().maxConcurrentSends());
        Gauge.builder("platform.webhook.queue.depth", depth, AtomicLong::get).register(meters);
        Gauge.builder("platform.webhook.queue.age", age, AtomicLong::get).baseUnit("seconds").register(meters);
    }

    // ---- the loop ----

    /**
     * One look: claims what is due, as many as there are free places to send from, and starts each on a thread of its
     * own. It does not wait for them.
     *
     * @return how many were claimed
     */
    synchronized int pollOnce() {
        int free = slots.availablePermits();
        if (free <= 0) {
            return 0;
        }
        List<Claimed> claimed = asDispatcher(() -> deliveries.claim(Math.min(settings.worker().batchSize(), free),
                settings.worker().perOrganization(), settings.worker().lease()));
        for (Claimed delivery : claimed) {
            if (!slots.tryAcquire()) {
                // Cannot happen with a single poller; if it does the lease simply expires and another look takes it.
                continue;
            }
            inFlight.incrementAndGet();
            workers.execute(() -> {
                try {
                    handle(delivery);
                } finally {
                    inFlight.decrementAndGet();
                    slots.release();
                }
            });
        }
        refreshGauges();
        return claimed.size();
    }

    /** How many deliveries this instance is working on. */
    int inFlight() {
        return inFlight.get();
    }

    /**
     * Looks and waits until this instance has nothing under way and the last look found nothing, for a test or a
     * shutdown that must not leave a send half done. Returns false if the time ran out first.
     */
    boolean drain(Duration limit) {
        long deadline = System.nanoTime() + limit.toNanos();
        int quiet = 0;
        while (System.nanoTime() < deadline) {
            int claimed = pollOnce();
            if (claimed == 0 && inFlight.get() == 0) {
                if (++quiet >= 2) {
                    return true;
                }
            } else {
                quiet = 0;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private void handle(Claimed claimed) {
        try {
            contexts.run(TenantContext.of(new TenantId(claimed.tenantId())), () -> process(claimed));
        } catch (RuntimeException failure) {
            // The type only: the message of a library can quote data. The lease ends and the delivery comes back.
            LOG.warn("The webhook worker failed on a delivery: {}", failure.getClass().getName());
            tracker.track(ErrorReport.current(new ConnectionTesting.IntegrationFailure(failure), "WORKER",
                    "integration.webhooks"));
            meters.counter("platform.webhook.worker", "result", "failed").increment();
        }
    }

    private void process(Claimed claimed) {
        Begun begun = transaction.execute(status -> begin(claimed));
        if (begun == null || begun.ready() == null) {
            return;
        }
        Ready ready = begun.ready();
        long started = System.nanoTime();
        Answer answer = call(claimed, ready);
        long took = Duration.ofNanos(System.nanoTime() - started).toMillis();
        Timer.builder("platform.webhook.send.duration").tag("result", answer.failure() == null ? "answered" : "failed")
                .register(meters).record(Duration.ofMillis(took));
        transaction.executeWithoutResult(status -> conclude(ready, answer, took));
    }

    // ---- the first transaction: decide, ask the gate, take the right to make the attempt ----

    private Begun begin(Claimed claimed) {
        Instant now = clock.instant();
        Subscription subscription = subscriptions.find(claimed.subscriptionId()).orElse(null);
        Delivery delivery = deliveries.findAny(claimed.id()).orElse(null);
        if (delivery == null || !DeliveryState.valueOf(delivery.state()).open()) {
            return Begun.nothing();
        }
        if (subscription == null) {
            deliveries.end(delivery.id(), "SKIPPED", WebhookReason.SUBSCRIPTION_REMOVED.key(), null, null);
            count("skipped", WebhookReason.SUBSCRIPTION_REMOVED.key());
            return Begun.nothing();
        }
        Instant later = now.plus(settings.queue().idleRecheck());
        if (!entitlements.enabled(FEATURE)) {
            deliveries.defer(delivery.id(), later, WebhookReason.PLAN_OFF.key());
            return Begun.nothing();
        }
        if (!SubscriptionMachine.sending(subscription.state())) {
            // Not lost: a subscription that is switched off holds its queue, which is bounded, until it is switched on.
            deliveries.deferSubscription(subscription.id(), later, WebhookReason.SUBSCRIPTION_OFF.key(), DEFER_BATCH);
            deliveries.defer(delivery.id(), later, WebhookReason.SUBSCRIPTION_OFF.key());
            return Begun.nothing();
        }
        if ("SENDING".equals(delivery.state())) {
            // An instance stopped while this was being sent. It may have reached the receiver, so the repeat carries
            // the same delivery identifier; the lost attempt is written down so the story reads true.
            attempts.record(delivery.id(), subscription.id(), delivery.generation(), Math.max(1, delivery.attempts()),
                    delivery.lastAttemptAt() == null ? now : delivery.lastAttemptAt(), 0, Outcome.INTERRUPTED,
                    FlowReason.INTERRUPTED.key(), "none", null);
            if (!RetryPolicy.mayTryAgain(settings.retry(), delivery.attempts())) {
                deadLetter(subscription, delivery, FlowReason.INTERRUPTED.key(), "none", null);
                return Begun.nothing();
            }
        }
        WebhookVisibility.Verdict verdict = visibility.judge(subscription, delivery.eventType(), delivery.object(),
                delivery.recordId());
        if (verdict != WebhookVisibility.Verdict.SEND) {
            WebhookReason reason = switch (verdict) {
                case MEMBER_INACTIVE -> WebhookReason.MEMBER_INACTIVE;
                case OBJECT_NOT_LISTED -> WebhookReason.OBJECT_NOT_LISTED;
                default -> WebhookReason.NOT_VISIBLE;
            };
            // Set aside, counted and visible; no record is named in the count, the log or the metric.
            deliveries.end(delivery.id(), "SKIPPED", reason.key(), null, null);
            count("skipped", reason.key());
            return Begun.nothing();
        }
        EndpointRules.Result address = EndpointRules.parse(subscription.address(), policy);
        if (!address.ok()) {
            Delivery counted = deliveries.admit(delivery.id(), settings.worker().lease()).orElse(delivery);
            attempts.record(counted.id(), subscription.id(), counted.generation(), Math.max(1, counted.attempts()), now,
                    0, Outcome.PERMANENT, TestReason.ADDRESS_NOT_ALLOWED.key(), "none", null);
            deadLetter(subscription, counted, TestReason.ADDRESS_NOT_ALLOWED.key(), "none", null);
            return Begun.nothing();
        }
        FlowGate.Verdict gated = gate.admit(subscription.id(), delivery.id(), now);
        if (!gated.admitted()) {
            if (FlowReason.CIRCUIT_OPEN.key().equals(gated.reason())) {
                deliveries.deferSubscription(subscription.id(), gated.retryAt(), gated.reason(), DEFER_BATCH);
            } else {
                meters.counter("platform.webhook.throttled").increment();
            }
            deliveries.defer(delivery.id(), gated.retryAt(), gated.reason());
            return Begun.nothing();
        }
        Delivery admitted = deliveries.admit(delivery.id(), settings.worker().lease()).orElse(null);
        if (admitted == null) {
            gate.report(subscription.id(), RetryPolicy.Health.NEUTRAL, now);
            return Begun.nothing();
        }
        return new Begun(new Ready(subscription, admitted, address.endpoint(), secretsOf(subscription, now), now));
    }

    /** The numbers of the secrets that sign: the current one, and the previous one while it still overlaps. */
    private static List<Integer> secretsOf(Subscription subscription, Instant now) {
        List<Integer> numbers = new ArrayList<>();
        numbers.add(subscription.secretNumber());
        if (subscription.previousSecretNumber() != null && subscription.previousSecretUntil() != null
                && subscription.previousSecretUntil().isAfter(now)) {
            numbers.add(subscription.previousSecretNumber());
        }
        return numbers;
    }

    // ---- the call: no transaction, one deadline ----

    private Answer call(Claimed claimed, Ready ready) {
        Delivery delivery = ready.delivery();
        byte[] body = WebhookPayload.record(delivery.id(), delivery.eventType(), delivery.createdAt(),
                delivery.object(), delivery.recordId(), delivery.recordVersion());
        return caller.send(new WebhookCaller.Request(new TenantId(claimed.tenantId()), ready.subscription().id(),
                ready.secrets(), ready.endpoint(), delivery.eventType(), delivery.id(), delivery.attempts(), body));
    }

    // ---- the last transaction: record how it ended ----

    private void conclude(Ready ready, Answer answer, long tookMillis) {
        Instant now = clock.instant();
        Delivery delivery = ready.delivery();
        Subscription subscription = ready.subscription();
        Integer status = answer.failure() == null ? answer.status() : null;
        String statusClass = status == null ? "none" : RetryPolicy.statusClass(status);
        if (answer.failure() == null && status >= 200 && status < 300) {
            deliveries.end(delivery.id(), "DONE", null, statusClass, status);
            attempts.record(delivery.id(), subscription.id(), delivery.generation(), delivery.attempts(),
                    ready.startedAt(), tookMillis, Outcome.SUCCESS, null, statusClass, status);
            gate.report(subscription.id(), RetryPolicy.Health.ALIVE, now);
            succeeded(subscription, now);
            count("done", "none");
            retention.maybePurge();
            return;
        }
        RetryPolicy.Verdict verdict = answer.failure() != null ? RetryPolicy.failure(answer.failure())
                : RetryPolicy.status(status, false);
        failed(ready, now, tookMillis, new Ended(verdict, status, statusClass, answer.retryAfterSeconds()));
    }

    private void succeeded(Subscription subscription, Instant now) {
        subscriptions.markSuccess(subscription.id(), now);
        Subscription held = subscriptions.lock(subscription.id()).orElse(null);
        if (held != null && held.state() == SubscriptionMachine.State.FAILING) {
            SubscriptionMachine.next(held.state(), SubscriptionMachine.Event.RECOVER, held.verified())
                    .ifPresent(next -> subscriptions.setState(held.id(), null, next, null,
                            RateLimiter.capacity(settings.delivery().perMinute()), SYSTEM));
        }
    }

    private void failed(Ready ready, Instant now, long tookMillis, Ended ended) {
        Delivery delivery = ready.delivery();
        Subscription subscription = ready.subscription();
        RetryPolicy.Verdict verdict = ended.verdict();
        boolean again = verdict.retry() && RetryPolicy.mayTryAgain(settings.retry(), delivery.attempts());
        attempts.record(delivery.id(), subscription.id(), delivery.generation(), delivery.attempts(),
                ready.startedAt(), tookMillis, again ? Outcome.RETRY : Outcome.PERMANENT, verdict.reason(),
                ended.statusClass(), ended.status());
        gate.report(subscription.id(), verdict.health(), now);
        markFailing(subscription, verdict.reason(), now);
        if (again) {
            Duration wait = RetryPolicy.delay(settings.retry(), delivery.attempts(),
                    ThreadLocalRandom.current().nextDouble(), verdict.honourRetryAfter() ? ended.retryAfter() : null);
            deliveries.retryLater(delivery.id(), now.plus(wait), verdict.reason(), ended.statusClass(),
                    ended.status());
            count("retry", verdict.reason());
            return;
        }
        deadLetter(subscription, delivery, verdict.reason(), ended.statusClass(), ended.status());
    }

    private void deadLetter(Subscription subscription, Delivery delivery, String reason, String statusClass,
            Integer status) {
        if (deliveries.end(delivery.id(), "DEAD_LETTERED", reason, statusClass, status)) {
            count("dead_lettered", reason);
            audit.record(AuditRecord.of("webhooks.delivery.dead_lettered", AuditOutcome.FAILURE).forUser(SYSTEM)
                    .inTenant(contexts.require().tenantId()).with("subscription", subscription.id().toString())
                    .with("delivery", delivery.id().toString()).with("reason", reason)
                    .with("attempts", Integer.toString(delivery.attempts())));
        }
    }

    /**
     * An attempt that did not succeed: the run of failures starts or goes on, an active subscription becomes
     * failing, and one that has failed without one success for the whole period is switched off by the platform, the
     * administrators told, nothing deleted (ADR-0279).
     */
    private void markFailing(Subscription subscription, String reason, Instant now) {
        subscriptions.markFailure(subscription.id(), now, reason);
        Subscription held = subscriptions.lock(subscription.id()).orElse(null);
        if (held == null) {
            return;
        }
        if (held.state() == SubscriptionMachine.State.ACTIVE) {
            SubscriptionMachine.next(held.state(), SubscriptionMachine.Event.FAIL, held.verified())
                    .ifPresent(next -> subscriptions.setState(held.id(), null, next, null, 0, SYSTEM));
        }
        Instant since = held.failingSince() == null ? now : held.failingSince();
        if (held.state() != SubscriptionMachine.State.DISABLED
                && !since.plus(settings.disableAfter()).isAfter(now)) {
            switchOff(held, since);
        }
    }

    private void switchOff(Subscription held, Instant since) {
        SubscriptionMachine.next(held.state(), SubscriptionMachine.Event.AUTO_DISABLE, held.verified())
                .ifPresent(next -> {
                    subscriptions.setState(held.id(), null, next,
                            SubscriptionMachine.disabledReason(SubscriptionMachine.Event.AUTO_DISABLE), 0, SYSTEM);
                    meters.counter("platform.webhook.disabled").increment();
                    audit.record(AuditRecord.of("webhooks.auto_disabled", AuditOutcome.SUCCESS).forUser(SYSTEM)
                            .inTenant(contexts.require().tenantId()).with("subscription", held.id().toString()));
                    tell(held, since);
                });
    }

    /** Tells every administrator, and the member the subscription acts as, once; the words are the platform's own. */
    private void tell(Subscription held, Instant since) {
        Set<UUID> people = new HashSet<>(access.administrators());
        people.add(held.userId());
        for (UUID person : people) {
            notices.notify(new NoticeRequest(NoticeKind.WEBHOOK_STOPPED, person, null, null,
                    java.util.Map.of("event", "auto_disabled"), "webhook-stopped:" + held.id() + ":"
                            + since.getEpochSecond() + ":" + person));
        }
    }

    private void count(String result, String reason) {
        meters.counter("platform.webhook.attempts", "result", result, "reason", reason == null ? "none" : reason)
                .increment();
        if ("skipped".equals(result)) {
            meters.counter("platform.webhook.skipped", "reason", reason).increment();
        }
    }

    // ---- counting the queue across organizations (a gauge; never per organization) ----

    /** What this instance last measured of the queue across organizations: counts and an age, no name. */
    record Sample(long open, long oldestSeconds, long deadLetters) {
    }

    Sample sample() {
        return new Sample(depth.get(), age.get(), dead.get());
    }

    private void refreshGauges() {
        try {
            depth.set(asDispatcher(deliveries::openAcrossOrganizations));
            age.set(asDispatcher(deliveries::oldestDueAgeSeconds));
            dead.set(asDispatcher(deliveries::deadLettersAcrossOrganizations));
        } catch (RuntimeException failure) {
            LOG.warn("The webhook worker could not count: {}", failure.getClass().getName());
        }
    }

    private <T> T asDispatcher(java.util.function.Supplier<T> work) {
        return contexts.callAsSystem(SystemScope.WEBHOOK_DISPATCH, () -> transaction.execute(s -> work.get()));
    }

    // ---- lifecycle ----

    @Override
    public synchronized void start() {
        if (running || !settings.enabled()) {
            return;
        }
        // A stopped worker may be started again (a lifecycle stop and start): its executor was shut down by stop.
        if (workers.isShutdown()) {
            workers = Executors.newVirtualThreadPerTaskExecutor();
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> new Thread(runnable, "webhook-worker"));
        running = true;
        long interval = settings.worker().pollInterval().toMillis();
        scheduler.scheduleWithFixedDelay(this::safePoll, interval, interval, TimeUnit.MILLISECONDS);
        LOG.info("The webhook worker started");
    }

    @Override
    public synchronized void stop() {
        running = false;
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
            scheduler = null;
        }
        // Work under way is given the time of one send; what is cut off is claimed again when its lease ends.
        workers.shutdown();
        try {
            if (!workers.awaitTermination(settings.delivery().sendTimeout().toSeconds() + 2, TimeUnit.SECONDS)) {
                workers.shutdownNow();
            }
        } catch (InterruptedException e) {
            workers.shutdownNow();
            Thread.currentThread().interrupt();
        }
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
            } while (claimed > 0 && running);
        } catch (RuntimeException failure) {
            LOG.warn("A webhook worker poll failed: {}", failure.getClass().getName());
        }
    }
}
