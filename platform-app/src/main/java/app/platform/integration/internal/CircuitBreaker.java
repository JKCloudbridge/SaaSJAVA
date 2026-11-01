package app.platform.integration.internal;

import java.time.Duration;
import java.time.Instant;

/**
 * The circuit breaker of one connection as a pure state machine (Sprint 29, ADR-0229). No database, no clock of its
 * own, no thread: the caller reads the state under a lock, asks, and stores what comes back, so every instance agrees
 * and every rule is tested as a property.
 *
 * <p>Closed lets everything through and counts failures in a row. Enough of them open it. Open lets nothing through
 * until its time is up, then it is half open: it lets a bounded number of probes through, and one success closes it,
 * one failure opens it again. An administrator can force it open (nothing is sent) or closed (it never opens); both
 * are lifted by returning it to automatic.
 *
 * <p>It is runtime state and never a state of the connection: a connection that is off is unusable whatever this says.
 * A failure of the platform's own side ({@link RetryPolicy.Health#NEUTRAL}) only frees a probe slot.
 */
final class CircuitBreaker {

    /** Where the circuit is. */
    enum Mode {
        CLOSED,
        OPEN,
        HALF_OPEN
    }

    /** What an administrator has forced, or null for automatic. */
    enum Forced {
        OPEN,
        CLOSED
    }

    /** How a send may proceed. */
    enum Admission {
        /** Send. */
        ADMIT,
        /** Send as the probe of a half-open circuit. */
        PROBE,
        /** Do not send now; wait until {@code retryAt}. */
        DEFER
    }

    /**
     * The state to keep.
     *
     * @param mode where the circuit is
     * @param consecutiveFailures failures in a row since the last sign of life
     * @param openUntil when an open circuit may let a probe through; null while closed
     * @param probesInFlight probes admitted and not yet ended
     * @param probeStartedAt when the oldest probe in flight was admitted
     * @param forced what an administrator forced, or null
     */
    record State(Mode mode, int consecutiveFailures, Instant openUntil, int probesInFlight, Instant probeStartedAt,
            Forced forced) {
    }

    /**
     * The settings that matter here.
     *
     * @param failureThreshold failures in a row that open the circuit
     * @param openFor how long it stays open
     * @param probes how many probes a half-open circuit lets through at once
     * @param probeTimeout how long a probe may be in flight before its slot is freed
     */
    record Config(int failureThreshold, Duration openFor, int probes, Duration probeTimeout) {

        static Config of(IntegrationProperties.Circuit circuit) {
            return new Config(circuit.failureThreshold(), circuit.openFor(), circuit.probes(),
                    circuit.probeTimeout());
        }
    }

    /**
     * The answer to a request to send.
     *
     * @param admission whether to send
     * @param state the state to store, whatever the admission
     * @param retryAt when to ask again, for a deferral
     * @param reason the stable reason of a deferral
     */
    record Decision(Admission admission, State state, Instant retryAt, String reason) {
    }

    /** How soon a half-open circuit whose probes are all in flight is asked again. */
    static final Duration HALF_OPEN_RECHECK = Duration.ofSeconds(1);

    private CircuitBreaker() {
    }

    /** A circuit that has never failed. */
    static State closed() {
        return new State(Mode.CLOSED, 0, null, 0, null, null);
    }

    /** Whether a send may be made now, and the state that follows. */
    static Decision admit(State state, Instant now, Config config) {
        if (state.forced() == Forced.OPEN) {
            return new Decision(Admission.DEFER, state, null, FlowReason.CIRCUIT_OPEN.key());
        }
        if (state.forced() == Forced.CLOSED) {
            return new Decision(Admission.ADMIT, state, null, null);
        }
        State current = state;
        if (current.mode() == Mode.OPEN) {
            if (now.isBefore(current.openUntil())) {
                return new Decision(Admission.DEFER, current, current.openUntil(), FlowReason.CIRCUIT_OPEN.key());
            }
            current = new State(Mode.HALF_OPEN, current.consecutiveFailures(), current.openUntil(), 0, null,
                    null);
        }
        if (current.mode() == Mode.HALF_OPEN) {
            int inFlight = current.probesInFlight();
            Instant started = current.probeStartedAt();
            if (inFlight > 0 && started != null && started.plus(config.probeTimeout()).isBefore(now)) {
                inFlight = 0;
                started = null;
            }
            if (inFlight >= config.probes()) {
                State kept = new State(Mode.HALF_OPEN, current.consecutiveFailures(), current.openUntil(), inFlight,
                        started, null);
                return new Decision(Admission.DEFER, kept, now.plus(HALF_OPEN_RECHECK),
                        FlowReason.CIRCUIT_OPEN.key());
            }
            State probing = new State(Mode.HALF_OPEN, current.consecutiveFailures(), current.openUntil(),
                    inFlight + 1, started == null ? now : started, null);
            return new Decision(Admission.PROBE, probing, null, null);
        }
        return new Decision(Admission.ADMIT, current, null, null);
    }

    /** The service showed it is alive: a half-open circuit closes, a closed one forgets its failures. */
    static State alive(State state) {
        return switch (state.mode()) {
            case HALF_OPEN -> new State(Mode.CLOSED, 0, null, 0, null, state.forced());
            case CLOSED -> new State(Mode.CLOSED, 0, null, 0, null, state.forced());
            // A late answer of a send made before the circuit opened proves nothing about now.
            case OPEN -> state;
        };
    }

    /** The service failed: failures add up and may open the circuit, and a probe that fails opens it again. */
    static State unhealthy(State state, Instant now, Config config) {
        if (state.forced() == Forced.CLOSED) {
            return new State(state.mode(), Math.min(state.consecutiveFailures() + 1, config.failureThreshold()),
                    state.openUntil(), state.probesInFlight(), state.probeStartedAt(), state.forced());
        }
        return switch (state.mode()) {
            case CLOSED -> {
                int failures = state.consecutiveFailures() + 1;
                yield failures >= config.failureThreshold()
                        ? new State(Mode.OPEN, failures, now.plus(config.openFor()), 0, null, state.forced())
                        : new State(Mode.CLOSED, failures, null, 0, null, state.forced());
            }
            case HALF_OPEN -> new State(Mode.OPEN, state.consecutiveFailures() + 1, now.plus(config.openFor()), 0,
                    null, state.forced());
            case OPEN -> state;
        };
    }

    /** The attempt showed nothing about the service: only a probe slot is freed. */
    static State neutral(State state) {
        if (state.mode() == Mode.HALF_OPEN && state.probesInFlight() > 0) {
            int left = state.probesInFlight() - 1;
            return new State(Mode.HALF_OPEN, state.consecutiveFailures(), state.openUntil(), left,
                    left == 0 ? null : state.probeStartedAt(), state.forced());
        }
        return state;
    }

    /** An administrator forces the circuit; the rest of the state is kept so that going back to automatic is exact. */
    static State force(State state, Forced forced) {
        return new State(state.mode(), state.consecutiveFailures(), state.openUntil(), state.probesInFlight(),
                state.probeStartedAt(), forced);
    }

    /** Back to automatic and closed: the failures counted before are forgotten. */
    static State automatic() {
        return closed();
    }
}
