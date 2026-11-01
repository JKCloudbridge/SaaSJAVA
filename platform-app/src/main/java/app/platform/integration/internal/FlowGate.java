package app.platform.integration.internal;

import app.platform.integration.internal.CircuitBreaker.Admission;
import app.platform.integration.internal.CircuitBreaker.Decision;
import app.platform.integration.internal.CircuitBreaker.Forced;
import app.platform.integration.internal.CircuitBreaker.Mode;
import app.platform.integration.internal.CircuitBreaker.State;
import app.platform.integration.internal.FlowStateStore.Flow;
import app.platform.integration.internal.RateLimiter.Take;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The decision whether a connection may send now (Sprint 29, ADR-0229, ADR-0232): the circuit breaker, the limit on how
 * many sends are under way and the token bucket, asked in that order under the lock of the connection's flow-state row.
 * Because the row is locked, two instances cannot both take the last token, both send the probe of a half-open circuit
 * or both exceed the concurrency, and the answer is the same whichever instance asks.
 *
 * <p>Every method runs inside a transaction in the organization's own tenant context. Nothing here is a state of the
 * connection: a connection that is off is not asked about at all, and nothing here switches one on or off.
 *
 * <p>The three checks never charge for a refusal: a send the circuit refuses uses no token, and a send that finds no
 * token does not count as a probe. A throttled or paused send waits; it never fails.
 */
@Component
class FlowGate {

    /** The retry time of a send that found the connection busy. */
    private static final Duration BUSY_RECHECK = Duration.ofSeconds(1);

    /**
     * The answer to "may this delivery be sent now?".
     *
     * @param admitted whether to send
     * @param probe whether this send is the probe of a half-open circuit
     * @param retryAt when to ask again, when not admitted
     * @param reason the stable reason it waits, when not admitted
     */
    record Verdict(boolean admitted, boolean probe, Instant retryAt, String reason) {

        static Verdict send(boolean probe) {
            return new Verdict(true, probe, null, null);
        }

        static Verdict later(Instant until, FlowReason reason) {
            return new Verdict(false, false, until, reason.key());
        }
    }

    /**
     * How a connection's flow stands, for the screens and the metrics.
     *
     * @param circuit where the breaker is (a half-open circuit is shown as such once its time is up)
     * @param forced what an administrator forced, or null
     * @param ratePerMinute the rate in force
     * @param maxConcurrent the concurrency in force
     */
    record Summary(Mode circuit, Forced forced, int consecutiveFailures, Instant openUntil, Instant openedAt,
            Instant lastSuccessAt, Instant lastFailureAt, String lastFailureReason, int ratePerMinute,
            int maxConcurrent, boolean ratePerMinuteIsDefault, boolean maxConcurrentIsDefault) {
    }

    private final FlowStateStore flows;
    private final DeliveryStore deliveries;
    private final IntegrationProperties.Reliability settings;
    private final CircuitBreaker.Config circuit;
    private final MeterRegistry meters;

    FlowGate(FlowStateStore flows, DeliveryStore deliveries, IntegrationProperties properties, MeterRegistry meters) {
        this.flows = flows;
        this.deliveries = deliveries;
        this.settings = properties.reliability();
        this.circuit = CircuitBreaker.Config.of(settings.circuit());
        this.meters = meters;
    }

    /** Asks whether the delivery may be sent now; if so, the token and the probe slot are taken. */
    Verdict admit(UUID connectionId, UUID deliveryId, Instant now) {
        IntegrationProperties.Rate rate = settings.rate();
        Flow flow = flows.lock(connectionId, RateLimiter.capacity(rate.defaultPerMinute()));
        int perMinute = effectiveRate(flow);
        int concurrent = effectiveConcurrency(flow);

        Decision decision = CircuitBreaker.admit(flow.circuit(), now, circuit);
        if (decision.admission() == Admission.DEFER) {
            if (!decision.state().equals(flow.circuit())) {
                flows.save(flow.id(), decision.state(), flow.bucket(), flow.openedAt(), flow.lastSuccessAt(),
                        flow.lastFailureAt(), flow.lastFailureReason());
            }
            Instant until = decision.retryAt() != null ? decision.retryAt() : now.plus(settings.queue().idleRecheck());
            count("circuit_open");
            return Verdict.later(until, FlowReason.CIRCUIT_OPEN);
        }
        if (deliveries.inFlight(connectionId, deliveryId) >= concurrent) {
            count("busy");
            return Verdict.later(now.plus(BUSY_RECHECK), FlowReason.THROTTLED);
        }
        Take take = RateLimiter.take(flow.bucket(), now, perMinute);
        if (!take.admitted()) {
            flows.save(flow.id(), flow.circuit(), take.bucket(), flow.openedAt(), flow.lastSuccessAt(),
                    flow.lastFailureAt(), flow.lastFailureReason());
            count("throttled");
            return Verdict.later(now.plus(take.delay()), FlowReason.THROTTLED);
        }
        boolean probe = decision.admission() == Admission.PROBE;
        flows.save(flow.id(), decision.state(), take.bucket(), flow.openedAt(), flow.lastSuccessAt(),
                flow.lastFailureAt(), flow.lastFailureReason());
        if (decision.state().mode() != flow.circuit().mode()) {
            transition(decision.state().mode());
        }
        return Verdict.send(probe);
    }

    /**
     * Tells the breaker how an attempt that was admitted ended.
     *
     * @param success whether the entity was really accepted (sets the time of the last success)
     * @param health what it showed about the other side
     * @param failureReason the stable reason of a failure, or null
     */
    void report(UUID connectionId, boolean success, RetryPolicy.Health health, String failureReason, Instant now) {
        Flow flow = flows.lock(connectionId, RateLimiter.capacity(settings.rate().defaultPerMinute()));
        State next = switch (health) {
            case ALIVE -> CircuitBreaker.alive(flow.circuit());
            case UNHEALTHY -> CircuitBreaker.unhealthy(flow.circuit(), now, circuit);
            case NEUTRAL -> CircuitBreaker.neutral(flow.circuit());
        };
        Instant openedAt = next.mode() == Mode.OPEN && flow.circuit().mode() != Mode.OPEN ? now : flow.openedAt();
        flows.save(flow.id(), next, flow.bucket(), openedAt, success ? now : flow.lastSuccessAt(),
                health == RetryPolicy.Health.UNHEALTHY ? now : flow.lastFailureAt(),
                health == RetryPolicy.Health.UNHEALTHY ? failureReason : flow.lastFailureReason());
        if (next.mode() != flow.circuit().mode()) {
            transition(next.mode());
        }
    }

    /** An administrator forces the circuit open or closed, or returns it to automatic (null). */
    Summary force(UUID connectionId, Forced forced, UUID by, Instant now) {
        Flow flow = flows.lock(connectionId, RateLimiter.capacity(settings.rate().defaultPerMinute()));
        if (forced == null) {
            flows.save(flow.id(), CircuitBreaker.automatic(), flow.bucket(), null, flow.lastSuccessAt(),
                    flow.lastFailureAt(), flow.lastFailureReason());
        }
        flows.markForced(flow.id(), forced, by);
        meters.counter("platform.integration.circuit.forced", "to", forced == null ? "automatic"
                : forced.name().toLowerCase(java.util.Locale.ROOT)).increment();
        return summary(connectionId, now);
    }

    /** Sets the operator's limits for the connection, inside the platform's ceilings (checked by the caller). */
    void limits(UUID connectionId, Integer ratePerMinute, Integer maxConcurrent) {
        Flow flow = flows.lock(connectionId, RateLimiter.capacity(settings.rate().defaultPerMinute()));
        flows.setLimits(flow.id(), ratePerMinute, maxConcurrent);
    }

    /** How the flow of the connection stands now (a connection never sent to is closed with the defaults). */
    Summary summary(UUID connectionId, Instant now) {
        Flow flow = flows.find(connectionId).orElse(null);
        if (flow == null) {
            return new Summary(Mode.CLOSED, null, 0, null, null, null, null, null, settings.rate().defaultPerMinute(),
                    settings.rate().defaultConcurrent(), true, true);
        }
        State state = flow.circuit();
        Mode shown = state.mode() == Mode.OPEN && state.openUntil() != null && !now.isBefore(state.openUntil())
                ? Mode.HALF_OPEN : state.mode();
        return new Summary(shown, state.forced(), state.consecutiveFailures(), state.openUntil(), flow.openedAt(),
                flow.lastSuccessAt(), flow.lastFailureAt(), flow.lastFailureReason(), effectiveRate(flow),
                effectiveConcurrency(flow), flow.ratePerMinute() == null, flow.maxConcurrent() == null);
    }

    int effectiveRate(Flow flow) {
        IntegrationProperties.Rate rate = settings.rate();
        return flow.ratePerMinute() == null ? rate.defaultPerMinute() : Math.min(flow.ratePerMinute(),
                rate.maxPerMinute());
    }

    int effectiveConcurrency(Flow flow) {
        IntegrationProperties.Rate rate = settings.rate();
        return flow.maxConcurrent() == null ? rate.defaultConcurrent() : Math.min(flow.maxConcurrent(),
                rate.maxConcurrent());
    }

    private void count(String result) {
        meters.counter("platform.integration.gate", "result", result).increment();
    }

    private void transition(Mode to) {
        meters.counter("platform.integration.circuit", "state", to.name().toLowerCase(java.util.Locale.ROOT))
                .increment();
    }
}
