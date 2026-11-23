package app.platform.integration.internal;

import app.platform.integration.internal.CircuitBreaker.Admission;
import app.platform.integration.internal.CircuitBreaker.Decision;
import app.platform.integration.internal.CircuitBreaker.Mode;
import app.platform.integration.internal.CircuitBreaker.State;
import app.platform.integration.internal.RateLimiter.Take;
import app.platform.integration.internal.WebhookSubscriptionStore.Subscription;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The decision whether a subscription's address may be called now (Sprint 32, ADR-0279): the circuit breaker, the limit
 * on how many calls to the address are under way and the token bucket, asked in that order under the lock of the
 * subscription's row. It is {@link FlowGate} for a webhook, with the same pure classes ({@link CircuitBreaker},
 * {@link RateLimiter}); the row is the subscription's own because a webhook is not a connection and the flow table
 * belongs to connections. Because the row is locked, two instances cannot both take the last token, both send the probe
 * of a half-open circuit or both exceed the concurrency.
 *
 * <p>Every method runs inside a transaction in the organization's own tenant context. The three checks never charge
 * for a
 * refusal, and a refused delivery waits, it never fails: a breaker that is open never sends, a throttle never loses a
 * delivery.
 */
@Component
class WebhookGate {

    /** The retry time of a delivery that found the address busy. */
    private static final Duration BUSY_RECHECK = Duration.ofSeconds(1);

    private final WebhookSubscriptionStore subscriptions;
    private final WebhookDeliveryStore deliveries;
    private final WebhookProperties settings;
    private final CircuitBreaker.Config circuit;
    private final MeterRegistry meters;

    WebhookGate(WebhookSubscriptionStore subscriptions, WebhookDeliveryStore deliveries, WebhookProperties settings,
            MeterRegistry meters) {
        this.subscriptions = subscriptions;
        this.deliveries = deliveries;
        this.settings = settings;
        this.circuit = CircuitBreaker.Config.of(settings.circuit());
        this.meters = meters;
    }

    /** Asks whether the delivery may be sent now; if so, the token and the probe slot are taken. */
    FlowGate.Verdict admit(UUID subscriptionId, UUID deliveryId, Instant now) {
        Subscription subscription = subscriptions.lock(subscriptionId).orElse(null);
        if (subscription == null) {
            return FlowGate.Verdict.later(now.plus(settings.queue().idleRecheck()), FlowReason.CONNECTION_REMOVED);
        }
        Decision decision = CircuitBreaker.admit(subscription.circuit(), now, circuit);
        if (decision.admission() == Admission.DEFER) {
            if (!decision.state().equals(subscription.circuit())) {
                subscriptions.saveFlow(subscriptionId, decision.state(), subscription.bucket(),
                        subscription.openedAt());
            }
            Instant until = decision.retryAt() != null ? decision.retryAt()
                    : now.plus(settings.queue().idleRecheck());
            count("circuit_open");
            return FlowGate.Verdict.later(until, FlowReason.CIRCUIT_OPEN);
        }
        if (deliveries.inFlight(subscriptionId, deliveryId) >= settings.delivery().maxInFlight()) {
            count("busy");
            return FlowGate.Verdict.later(now.plus(BUSY_RECHECK), FlowReason.THROTTLED);
        }
        Take take = RateLimiter.take(subscription.bucket(), now, settings.delivery().perMinute());
        if (!take.admitted()) {
            subscriptions.saveFlow(subscriptionId, subscription.circuit(), take.bucket(), subscription.openedAt());
            count("throttled");
            return FlowGate.Verdict.later(now.plus(take.delay()), FlowReason.THROTTLED);
        }
        subscriptions.saveFlow(subscriptionId, decision.state(), take.bucket(), subscription.openedAt());
        if (decision.state().mode() != subscription.circuit().mode()) {
            transition(decision.state().mode());
        }
        return FlowGate.Verdict.send(decision.admission() == Admission.PROBE);
    }

    /**
     * Tells the breaker how an attempt that was admitted ended.
     *
     * @param health what it showed about the other side
     */
    void report(UUID subscriptionId, RetryPolicy.Health health, Instant now) {
        Subscription subscription = subscriptions.lock(subscriptionId).orElse(null);
        if (subscription == null) {
            return;
        }
        State next = switch (health) {
            case ALIVE -> CircuitBreaker.alive(subscription.circuit());
            case UNHEALTHY -> CircuitBreaker.unhealthy(subscription.circuit(), now, circuit);
            case NEUTRAL -> CircuitBreaker.neutral(subscription.circuit());
        };
        Instant openedAt = next.mode() == Mode.OPEN && subscription.circuit().mode() != Mode.OPEN ? now
                : subscription.openedAt();
        subscriptions.saveFlow(subscriptionId, next, subscription.bucket(), openedAt);
        if (next.mode() != subscription.circuit().mode()) {
            transition(next.mode());
        }
    }

    private void count(String result) {
        meters.counter("platform.webhook.gate", "result", result).increment();
    }

    private void transition(Mode to) {
        meters.counter("platform.webhook.circuit", "state", to.name().toLowerCase(Locale.ROOT)).increment();
    }
}
