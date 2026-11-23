package app.platform.integration.internal;

import app.platform.integration.internal.EgressClient.Answer;
import app.platform.integration.internal.WebhookSubscriptionStore.Subscription;
import app.platform.security.Ability;
import app.platform.sharedkernel.TenantId;
import app.platform.sharedkernel.audit.AuditOutcome;
import app.platform.sharedkernel.audit.AuditRecord;
import app.platform.sharedkernel.audit.AuditRecorder;
import app.platform.tenant.TenantContext;
import app.platform.tenant.TenantContexts;
import app.platformapi.ApiException;
import app.platformapi.WebhookTestView;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sends a signed {@code ping} to the address of a subscription and records whether it answered (Sprint 32, ADR-0276):
 * the verification that a subscription needs before it can be switched on, and the test an administrator may send at
 * any
 * time. One bounded call, made outside every database transaction, in the organization's own context, and answered with
 * a stable reason and the platform's own sentence; nothing the other side said is read, kept or shown.
 *
 * <p>Three steps, as the test of a connection (ADR-0210). First a short transaction decides who may ask, loads the
 * subscription and rate limits the test (one a few seconds for each subscription, and a few at once on an instance).
 * Then the call, with no transaction held, under one hard deadline. Last a short transaction stores the result against
 * the fingerprint that was tested: if the subscription changed meanwhile the result is dropped and the answer says so,
 * so a verification can never vouch for an address it did not test.
 *
 * <p>The ping goes through {@link WebhookCaller} like every delivery, so it is signed with the current secrets, carries
 * the same four headers and is held to every rule for an outside address.
 */
@Service
class WebhookTesting {

    private static final int REMEMBER_BOUND = 10_000;

    /** What the first step decided. */
    private record Prepared(TenantContext caller, TenantId tenant, String fingerprint, EndpointRules.Endpoint endpoint,
            List<Integer> secrets) {
    }

    private final TransactionTemplate transaction;
    private final WebhookSubscriptionStore store;
    private final IntegrationAccess access;
    private final WebhookCaller caller;
    private final EndpointRules.Policy policy;
    private final WebhookProperties properties;
    private final TenantContexts contexts;
    private final AuditRecorder audit;
    private final MeterRegistry meters;
    private final Clock clock;
    private final Semaphore slots;
    private final ConcurrentHashMap<UUID, Long> lastAt = new ConcurrentHashMap<>();

    WebhookTesting(PlatformTransactionManager transactions, WebhookSubscriptionStore store, IntegrationAccess access,
            WebhookCaller caller, EndpointRules.Policy policy, WebhookProperties properties, TenantContexts contexts,
            AuditRecorder audit, MeterRegistry meters, Clock clock) {
        this.transaction = new TransactionTemplate(transactions);
        this.store = store;
        this.access = access;
        this.caller = caller;
        this.policy = policy;
        this.properties = properties;
        this.contexts = contexts;
        this.audit = audit;
        this.meters = meters;
        this.clock = clock;
        this.slots = new Semaphore(properties.test().maxConcurrent());
    }

    /** Sends the ping and answers. */
    WebhookTestView test(UUID id) {
        Prepared prepared = transaction.execute(status -> prepare(id));
        if (prepared == null) {
            throw ApiException.notFound("This webhook does not exist.");
        }
        if (!slots.tryAcquire()) {
            throw ApiException.rateLimited(1);
        }
        Answer sent;
        UUID ping = UUID.randomUUID();
        try {
            if (prepared.endpoint() == null) {
                sent = Answer.failed(TestReason.ADDRESS_NOT_ALLOWED);
            } else {
                sent = caller.send(new WebhookCaller.Request(prepared.tenant(), id, prepared.secrets(),
                        prepared.endpoint(), WebhookEvents.PING, ping, 1,
                        WebhookPayload.ping(ping, clock.instant())));
            }
        } finally {
            slots.release();
        }
        Answer answer = sent;
        return transaction.execute(status -> conclude(id, prepared, answer));
    }

    private Prepared prepare(UUID id) {
        TenantContext who = access.require(Ability.WEBHOOKS_VIEW, Ability.WEBHOOKS_MANAGE);
        Subscription subscription = store.find(id).orElseThrow(
                () -> ApiException.notFound("This webhook does not exist."));
        claim(id);
        EndpointRules.Result address = EndpointRules.parse(subscription.address(), policy);
        EndpointRules.Endpoint endpoint = address.ok() ? address.endpoint() : null;
        Instant now = clock.instant();
        boolean overlapping = subscription.previousSecretNumber() != null
                && subscription.previousSecretUntil() != null && subscription.previousSecretUntil().isAfter(now);
        List<Integer> secrets = overlapping
                ? List.of(subscription.secretNumber(), subscription.previousSecretNumber())
                : List.of(subscription.secretNumber());
        return new Prepared(who, contexts.require().tenantId(), subscription.fingerprint(), endpoint, secrets);
    }

    /** One test of one subscription every few seconds, whichever instance asks. */
    private void claim(UUID id) {
        long now = clock.millis();
        long minimum = properties.test().minInterval().toMillis();
        if (lastAt.size() > REMEMBER_BOUND) {
            lastAt.clear();
        }
        Long previous = lastAt.put(id, now);
        if (previous != null && now - previous < minimum) {
            lastAt.put(id, previous);
            throw ApiException.rateLimited(Math.max(1, (minimum - (now - previous) + 999) / 1000));
        }
    }

    private WebhookTestView conclude(UUID id, Prepared prepared, Answer answer) {
        Subscription current = store.lock(id).orElse(null);
        TestReason failure = answer.failure();
        boolean delivered = failure == null && answer.status() >= 200 && answer.status() < 300;
        String statusClass = failure == null ? RetryPolicy.statusClass(answer.status()) : "none";
        String reason = delivered ? "ok" : failure != null ? failure.key()
                : RetryPolicy.status(answer.status(), false).reason();
        String message = delivered ? "The address answered the test."
                : failure != null ? failure.words()
                : "The address answered, but not with a success. It must answer with a status from 200 to 299.";
        boolean verified = false;
        if (current == null) {
            throw ApiException.notFound("This webhook does not exist.");
        }
        if (!current.fingerprint().equals(prepared.fingerprint())) {
            delivered = false;
            reason = TestReason.CHANGED_DURING_TEST.key();
            message = TestReason.CHANGED_DURING_TEST.words();
        } else if (prepared.endpoint() == null) {
            delivered = false;
            reason = TestReason.ADDRESS_NOT_ALLOWED.key();
            message = TestReason.ADDRESS_NOT_ALLOWED.words();
        } else if (delivered) {
            store.markVerified(id, current.fingerprint());
            verified = true;
        }
        audit.record(AuditRecord.of("webhooks.tested", delivered ? AuditOutcome.SUCCESS : AuditOutcome.FAILURE)
                .forUser(prepared.caller().userId()).inTenant(prepared.tenant()).with("subscription", id.toString())
                .with("reason", reason));
        meters.counter("platform.webhook.tests", "result", delivered ? "ok" : "failed").increment();
        return new WebhookTestView(delivered, reason, statusClass, message, verified || current.verified());
    }
}
