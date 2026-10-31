package app.platform.integration.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.integration.internal.CircuitBreaker.Admission;
import app.platform.integration.internal.CircuitBreaker.Config;
import app.platform.integration.internal.CircuitBreaker.Decision;
import app.platform.integration.internal.CircuitBreaker.Forced;
import app.platform.integration.internal.CircuitBreaker.Mode;
import app.platform.integration.internal.CircuitBreaker.State;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import org.junit.jupiter.api.Test;

/** The circuit breaker as a state machine (Sprint 29, ADR-0229): the rules, then the invariants as properties. */
class CircuitBreakerTest {

    private static final Config CONFIG = new Config(3, Duration.ofSeconds(60), 1, Duration.ofMinutes(2));
    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void aClosedCircuitAdmitsAndCountsFailuresInARow() {
        State state = CircuitBreaker.closed();

        assertThat(CircuitBreaker.admit(state, T0, CONFIG).admission()).isEqualTo(Admission.ADMIT);
        state = CircuitBreaker.unhealthy(state, T0, CONFIG);
        state = CircuitBreaker.unhealthy(state, T0, CONFIG);
        assertThat(state.mode()).isEqualTo(Mode.CLOSED);
        assertThat(state.consecutiveFailures()).isEqualTo(2);
    }

    @Test
    void aSignOfLifeForgetsTheFailuresSoOnlyFailuresInARowOpenIt() {
        State state = CircuitBreaker.unhealthy(CircuitBreaker.closed(), T0, CONFIG);
        state = CircuitBreaker.unhealthy(state, T0, CONFIG);
        state = CircuitBreaker.alive(state);
        state = CircuitBreaker.unhealthy(state, T0, CONFIG);
        state = CircuitBreaker.unhealthy(state, T0, CONFIG);

        assertThat(state.mode()).isEqualTo(Mode.CLOSED);
    }

    @Test
    void theThresholdOpensItAndAnOpenCircuitDefersUntilItsTimeIsUp() {
        State state = open(T0);

        Decision before = CircuitBreaker.admit(state, T0.plusSeconds(59), CONFIG);

        assertThat(before.admission()).isEqualTo(Admission.DEFER);
        assertThat(before.reason()).isEqualTo("circuit_open");
        assertThat(before.retryAt()).isEqualTo(T0.plusSeconds(60));
    }

    @Test
    void afterItsTimeAHalfOpenCircuitLetsExactlyTheProbesThrough() {
        State state = open(T0);
        Instant later = T0.plusSeconds(61);

        Decision first = CircuitBreaker.admit(state, later, CONFIG);
        Decision second = CircuitBreaker.admit(first.state(), later, CONFIG);

        assertThat(first.admission()).isEqualTo(Admission.PROBE);
        assertThat(first.state().mode()).isEqualTo(Mode.HALF_OPEN);
        assertThat(second.admission()).isEqualTo(Admission.DEFER);
        assertThat(second.retryAt()).isEqualTo(later.plus(CircuitBreaker.HALF_OPEN_RECHECK));
    }

    @Test
    void aSuccessfulProbeClosesTheCircuitAndAFailedOneOpensItAgain() {
        Instant later = T0.plusSeconds(61);
        State probing = CircuitBreaker.admit(open(T0), later, CONFIG).state();

        assertThat(CircuitBreaker.alive(probing).mode()).isEqualTo(Mode.CLOSED);
        State failed = CircuitBreaker.unhealthy(probing, later, CONFIG);
        assertThat(failed.mode()).isEqualTo(Mode.OPEN);
        assertThat(failed.openUntil()).isEqualTo(later.plusSeconds(60));
        assertThat(failed.probesInFlight()).isZero();
    }

    @Test
    void anAttemptThatShowsNothingOnlyFreesTheProbeSlot() {
        State probing = CircuitBreaker.admit(open(T0), T0.plusSeconds(61), CONFIG).state();

        State freed = CircuitBreaker.neutral(probing);

        assertThat(freed.mode()).isEqualTo(Mode.HALF_OPEN);
        assertThat(freed.probesInFlight()).isZero();
        assertThat(CircuitBreaker.admit(freed, T0.plusSeconds(62), CONFIG).admission()).isEqualTo(Admission.PROBE);
    }

    @Test
    void aProbeThatNeverReportsIsGivenUpAfterTheProbeTimeout() {
        State probing = CircuitBreaker.admit(open(T0), T0.plusSeconds(61), CONFIG).state();

        Decision stuck = CircuitBreaker.admit(probing, T0.plusSeconds(61).plus(Duration.ofMinutes(3)), CONFIG);

        assertThat(stuck.admission()).isEqualTo(Admission.PROBE);
    }

    @Test
    void aLateAnswerOfASendMadeBeforeTheCircuitOpenedChangesNothing() {
        State state = open(T0);

        assertThat(CircuitBreaker.alive(state)).isEqualTo(state);
        assertThat(CircuitBreaker.unhealthy(state, T0.plusSeconds(5), CONFIG)).isEqualTo(state);
    }

    @Test
    void aCircuitForcedOpenNeverAdmitsAndOneForcedClosedNeverOpens() {
        State forcedOpen = CircuitBreaker.force(CircuitBreaker.closed(), Forced.OPEN);
        State forcedClosed = CircuitBreaker.force(CircuitBreaker.closed(), Forced.CLOSED);

        assertThat(CircuitBreaker.admit(forcedOpen, T0, CONFIG).admission()).isEqualTo(Admission.DEFER);
        State state = forcedClosed;
        for (int i = 0; i < 20; i++) {
            state = CircuitBreaker.unhealthy(state, T0, CONFIG);
        }
        assertThat(state.mode()).isEqualTo(Mode.CLOSED);
        assertThat(CircuitBreaker.admit(state, T0, CONFIG).admission()).isEqualTo(Admission.ADMIT);
    }

    @Test
    void goingBackToAutomaticForgetsEverything() {
        State state = CircuitBreaker.force(open(T0), Forced.OPEN);

        assertThat(CircuitBreaker.automatic()).isEqualTo(CircuitBreaker.closed());
        assertThat(CircuitBreaker.admit(CircuitBreaker.automatic(), T0, CONFIG).admission())
                .isEqualTo(Admission.ADMIT);
        assertThat(state.forced()).isEqualTo(Forced.OPEN);
    }

    /** The invariants, over any run of admissions, answers and passing time. */
    @Property(tries = 400)
    void itNeverSendsWhileOpenNeverExceedsTheProbesAndRecoversOnSuccess(
            @ForAll @Size(max = 80) List<@IntRange(min = 0, max = 399) Integer> steps,
            @ForAll @IntRange(min = 1, max = 3) int probes) {
        Config config = new Config(3, Duration.ofSeconds(60), probes, Duration.ofMinutes(2));
        State state = CircuitBreaker.closed();
        Instant now = T0;
        for (int code : steps) {
            now = now.plusSeconds(code / 4);
            switch (code % 4) {
                case 0 -> {
                    Decision decision = CircuitBreaker.admit(state, now, config);
                    if (state.mode() == Mode.OPEN && now.isBefore(state.openUntil())) {
                        assertThat(decision.admission()).as("open until %s, now %s", state.openUntil(), now)
                                .isEqualTo(Admission.DEFER);
                    }
                    if (decision.admission() == Admission.DEFER) {
                        assertThat(decision.retryAt()).isAfter(now.minusSeconds(1));
                    }
                    state = decision.state();
                    assertThat(state.probesInFlight()).isLessThanOrEqualTo(probes);
                }
                case 1 -> {
                    boolean halfOpen = state.mode() == Mode.HALF_OPEN;
                    state = CircuitBreaker.alive(state);
                    if (halfOpen) {
                        assertThat(state.mode()).isEqualTo(Mode.CLOSED);
                    }
                }
                case 2 -> state = CircuitBreaker.unhealthy(state, now, config);
                default -> state = CircuitBreaker.neutral(state);
            }
            assertThat(state.probesInFlight()).isBetween(0, probes);
            assertThat(state.consecutiveFailures()).isGreaterThanOrEqualTo(0);
            assertThat(state.mode() == Mode.CLOSED).as("closed means no time to wait").isEqualTo(
                    state.openUntil() == null);
        }
    }

    @Property(tries = 200)
    void aCircuitThatIsLeftAloneAndThenAnswersWellAlwaysEndsClosed(
            @ForAll @IntRange(min = 0, max = 30) int failures) {
        State state = CircuitBreaker.closed();
        Instant now = T0;
        for (int i = 0; i < failures; i++) {
            state = CircuitBreaker.unhealthy(state, now, CONFIG);
        }
        now = now.plus(Duration.ofMinutes(10));
        Decision decision = CircuitBreaker.admit(state, now, CONFIG);
        assertThat(decision.admission()).isIn(Admission.ADMIT, Admission.PROBE);
        state = CircuitBreaker.alive(decision.state());

        assertThat(state.mode()).isEqualTo(Mode.CLOSED);
        assertThat(CircuitBreaker.admit(state, now, CONFIG).admission()).isEqualTo(Admission.ADMIT);
    }

    private static State open(Instant at) {
        State state = CircuitBreaker.closed();
        for (int i = 0; i < CONFIG.failureThreshold(); i++) {
            state = CircuitBreaker.unhealthy(state, at, CONFIG);
        }
        assertThat(state.mode()).isEqualTo(Mode.OPEN);
        return state;
    }
}
