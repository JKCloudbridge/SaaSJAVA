package app.platform.notification.internal;

import app.platform.notification.internal.MailComposer.Decision;
import app.platform.notification.internal.MailStore.ClaimedMail;
import app.platform.observability.ErrorReport;
import app.platform.observability.ErrorTracker;
import app.platform.sharedkernel.audit.AuditOutcome;
import app.platform.sharedkernel.audit.AuditRecord;
import app.platform.sharedkernel.audit.AuditRecorder;
import app.platform.sharedkernel.logging.LogContext;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
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
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The mail relay: claims due mails, decides what each becomes, sends it and records the outcome (ADR-0024). Every
 * instance of the application may run one; they coordinate through the database only. Modelled on the outbox relay
 * (ADR-0016).
 *
 * <p><strong>Guarantees.</strong>
 * <ul>
 *   <li>A request is queued in the transaction of the change that caused it, so a rolled-back change sends
 *       nothing.</li>
 *   <li>Sending is asynchronous and survives outages: a message the server could not take is retried with a growing
 *       delay; a claim that is never completed (the instance died) expires and any instance takes it over, so a mail
 *       queued before a restart is sent after it.</li>
 *   <li>Delivery is at least once. A crash between the server accepting a message and the row being marked sent can
 *       send it twice; for a link mail the second message carries a newer token that replaces the first, which the
 *       text of the ADR states. After {@code maxAttempts} attempts, or when the mail is older than {@code maxAge}
 *       (its link would be stale), it becomes a dead letter that stays for a person to look at.</li>
 *   <li>Nothing secret is in the queue, a log line or an audit record: the token is created at send time and lives only
 *       in the message; failures are recorded by type name only.</li>
 * </ul>
 */
@Component
class MailRelay implements SmartLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(MailRelay.class);
    private static final int MAX_ERROR_TYPE = 200;
    private static final Duration PURGE_INTERVAL = Duration.ofMinutes(10);
    private static final Duration SHUTDOWN_WAIT = Duration.ofSeconds(25);

    private final MailStore store;
    private final MailComposer composer;
    private final MailTransport transport;
    private final MailProperties properties;
    private final TransactionTemplate transaction;
    private final AuditRecorder audit;
    private final ErrorTracker tracker;
    private final MeterRegistry meters;
    private final Clock clock;
    private final MailBackoff backoff;

    private ScheduledExecutorService executor;
    private volatile boolean running;

    MailRelay(MailStore store, MailComposer composer, MailTransport transport, MailProperties properties,
            TransactionTemplate transaction, AuditRecorder audit, ErrorTracker tracker, MeterRegistry meters,
            Clock clock) {
        this.store = store;
        this.composer = composer;
        this.transport = transport;
        this.properties = properties;
        this.transaction = transaction;
        this.audit = audit;
        this.tracker = tracker;
        this.meters = meters;
        this.clock = clock;
        this.backoff = new MailBackoff(properties.backoffInitial(), properties.backoffMax(),
                RandomGenerator.getDefault());
    }

    // ---- one poll ----

    /**
     * Claims one batch and handles it.
     *
     * @return how many mails were claimed; a full batch means there may be more waiting
     */
    int pollOnce() {
        List<ClaimedMail> claimed = transaction.execute(status -> store.claim(properties.batchSize(),
                properties.lease()));
        if (claimed == null) {
            return 0;
        }
        for (ClaimedMail mail : claimed) {
            process(mail);
        }
        return claimed.size();
    }

    /** Removes finished mails and old dead letters. */
    void purge() {
        int removed;
        do {
            removed = inTransaction(() -> store.purgeFinished(properties.sentRetention()));
        } while (removed > 0 && running);
        do {
            removed = inTransaction(() -> store.purgeDead(properties.deadRetention()));
        } while (removed > 0 && running);
    }

    private void process(ClaimedMail mail) {
        if (mail.attempt() > properties.maxAttempts()) {
            // Earlier handlings never reported back: the process died or stalled each time.
            deadLetter(mail, "AttemptsExhausted", new IllegalStateException("Mail attempts exhausted"));
            return;
        }
        if (Duration.between(mail.createdAt(), clock.instant()).compareTo(properties.maxAge()) > 0) {
            deadLetter(mail, "Expired", new IllegalStateException("Mail expired unsent"));
            return;
        }
        Decision decision;
        try {
            decision = composer.decide(mail);
        } catch (RuntimeException failure) {
            fail(mail, failure);
            return;
        }
        switch (decision) {
            case Decision.Suppress suppress -> suppressed(mail, suppress);
            case Decision.Send send -> {
                try {
                    transport.send(send.message());
                } catch (RuntimeException failure) {
                    fail(mail, failure);
                    return;
                }
                sent(mail, send);
            }
        }
    }

    private void sent(ClaimedMail mail, Decision.Send send) {
        if (inTransaction(() -> store.markSent(mail, send.outcome()))) {
            meters.counter("platform.notification.mail", "result", "sent").increment();
            audit.record(AuditRecord.of("notification.mail.sent", AuditOutcome.SUCCESS).forUser(mail.userId())
                    .with("template", mail.template().name()).with("outcome", send.outcome())
                    .with("attempt", Integer.toString(mail.attempt())));
            LOG.info("Mail {} of kind {} sent at attempt {}", mail.id(), mail.template(), mail.attempt());
        } else {
            LOG.info("Mail {} was taken over by another instance; its outcome is theirs", mail.id());
        }
    }

    private void suppressed(ClaimedMail mail, Decision.Suppress suppress) {
        if (inTransaction(() -> store.markSuppressed(mail, suppress.outcome()))) {
            meters.counter("platform.notification.mail", "result", "suppressed").increment();
            audit.record(AuditRecord.of("notification.mail.suppressed", AuditOutcome.DENIED).forUser(mail.userId())
                    .because(suppress.outcome()).with("template", mail.template().name()));
            LOG.debug("Mail {} of kind {} suppressed: {}", mail.id(), mail.template(), suppress.outcome());
        }
    }

    private void fail(ClaimedMail mail, RuntimeException failure) {
        String errorType = errorType(failure);
        if (mail.attempt() >= properties.maxAttempts()) {
            deadLetter(mail, errorType, failure);
            return;
        }
        Duration delay = backoff.delayAfter(mail.attempt());
        boolean recorded = inTransaction(() -> store.scheduleRetry(mail, delay, errorType));
        meters.counter("platform.notification.mail", "result", "retried").increment();
        LOG.atWarn()
                .addKeyValue("mail_id", mail.id())
                .addKeyValue("template", mail.template())
                .addKeyValue("attempt", mail.attempt())
                .addKeyValue("error_type", errorType)
                .addKeyValue("retry_in_ms", delay.toMillis())
                .addKeyValue("recorded", recorded)
                .log("Sending a mail failed, will retry");
    }

    private void deadLetter(ClaimedMail mail, String errorType, RuntimeException failure) {
        boolean recorded = inTransaction(() -> store.markDead(mail, errorType));
        meters.counter("platform.notification.mail", "result", "dead").increment();
        audit.record(AuditRecord.of("notification.mail.dead", AuditOutcome.FAILURE).forUser(mail.userId())
                .because("delivery_failed").with("template", mail.template().name())
                .with("error_type", errorType));
        LOG.atError()
                .addKeyValue("mail_id", mail.id())
                .addKeyValue("template", mail.template())
                .addKeyValue("attempt", mail.attempt())
                .addKeyValue("error_type", errorType)
                .addKeyValue("recorded", recorded)
                .log("A mail was set aside as a dead letter");
        tracker.track(new ErrorReport(failure, ErrorReport.UNKNOWN_REQUEST, LogContext.traceId(),
                LogContext.tenantId(), "MAIL", mail.template().name()));
    }

    private <T> T inTransaction(Supplier<T> work) {
        return transaction.execute(status -> work.get());
    }

    private static String errorType(RuntimeException failure) {
        String name = failure instanceof MailTransport.MailTransportException transportFailure
                ? transportFailure.causeType() : failure.getClass().getSimpleName();
        return name.length() > MAX_ERROR_TYPE ? name.substring(0, MAX_ERROR_TYPE) : name;
    }

    // ---- lifecycle ----

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        if (!properties.enabled()) {
            LOG.info("The mail relay is disabled on this instance");
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> new Thread(runnable, "mail-relay"));
        running = true;
        long interval = properties.pollInterval().toMillis();
        executor.scheduleWithFixedDelay(this::safePoll, interval, interval, TimeUnit.MILLISECONDS);
        executor.scheduleWithFixedDelay(this::safePurge, PURGE_INTERVAL.toMillis(), PURGE_INTERVAL.toMillis(),
                TimeUnit.MILLISECONDS);
        LOG.info("The mail relay started");
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
                // A claim that was never completed expires and the mail is claimed again by any instance.
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        executor = null;
        LOG.info("The mail relay stopped");
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
            // The scheduler must survive a database outage; the class is enough, driver text can quote data.
            LOG.warn("A mail poll failed: {}", failure.getClass().getName());
        }
    }

    private void safePurge() {
        try {
            purge();
        } catch (RuntimeException failure) {
            LOG.warn("The mail clean-up failed: {}", failure.getClass().getName());
        }
    }
}
