package app.platform.integration.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.integration.internal.ConnectionMachine.Event;
import app.platform.integration.internal.ConnectionMachine.State;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;

/**
 * The life of a connection (ADR-0210): the table is exactly what the database guard of V083 allows, a connection that
 * is on cannot be edited, and no run of events ever reaches a state through a door the rules do not have.
 */
class ConnectionMachineTest {

    @Test
    void aNewConnectionMustBeTestedBeforeItCanBeSwitchedOn() {
        assertThat(ConnectionMachine.next(State.UNTESTED, Event.ENABLE)).isEmpty();
        assertThat(ConnectionMachine.next(State.UNTESTED, Event.TEST_PASSED)).contains(State.TESTED);
        assertThat(ConnectionMachine.next(State.TESTED, Event.ENABLE)).contains(State.ENABLED);
    }

    @Test
    void anEditVoidsTheTestAndIsNotPossibleWhileTheConnectionIsOn() {
        assertThat(ConnectionMachine.next(State.TESTED, Event.EDIT)).contains(State.UNTESTED);
        assertThat(ConnectionMachine.next(State.DISABLED, Event.EDIT)).contains(State.UNTESTED);
        assertThat(ConnectionMachine.next(State.ENABLED, Event.EDIT)).isEmpty();
        assertThat(ConnectionMachine.next(State.FAILING, Event.EDIT)).isEmpty();
        assertThat(ConnectionMachine.canChange(State.ENABLED)).isFalse();
        assertThat(ConnectionMachine.canChange(State.FAILING)).isFalse();
        assertThat(ConnectionMachine.canChange(State.DISABLED)).isTrue();
    }

    @Test
    void aFailedTestOfAConnectionThatIsOnMakesItFailingAndAPassingOneRecoversIt() {
        assertThat(ConnectionMachine.next(State.ENABLED, Event.TEST_FAILED)).contains(State.FAILING);
        assertThat(ConnectionMachine.next(State.FAILING, Event.TEST_PASSED)).contains(State.ENABLED);
        assertThat(ConnectionMachine.next(State.ENABLED, Event.REPORT_FAILURE)).contains(State.FAILING);
    }

    @Test
    void onlyAnEnabledConnectionIsUsableAndSwitchingOffIsAlwaysPossibleFromOn() {
        for (State state : State.values()) {
            assertThat(ConnectionMachine.isUsable(state)).isEqualTo(state == State.ENABLED);
        }
        assertThat(ConnectionMachine.next(State.ENABLED, Event.DISABLE)).contains(State.DISABLED);
        assertThat(ConnectionMachine.next(State.FAILING, Event.DISABLE)).contains(State.DISABLED);
        assertThat(ConnectionMachine.next(State.UNTESTED, Event.DISABLE)).isEmpty();
    }

    @Test
    void everyMoveTheMachineHasIsOneTheDatabaseGuardHas() {
        // The guard of V083 lists: UNTESTED to UNTESTED or TESTED; TESTED to TESTED, UNTESTED or ENABLED;
        // ENABLED to ENABLED, DISABLED or FAILING; FAILING to FAILING, DISABLED or ENABLED; DISABLED to DISABLED,
        // UNTESTED or ENABLED.
        java.util.Map<State, EnumSet<State>> guard = java.util.Map.of(
                State.UNTESTED, EnumSet.of(State.UNTESTED, State.TESTED),
                State.TESTED, EnumSet.of(State.TESTED, State.UNTESTED, State.ENABLED),
                State.ENABLED, EnumSet.of(State.ENABLED, State.DISABLED, State.FAILING),
                State.FAILING, EnumSet.of(State.FAILING, State.DISABLED, State.ENABLED),
                State.DISABLED, EnumSet.of(State.DISABLED, State.UNTESTED, State.ENABLED));
        for (State from : State.values()) {
            for (Event event : Event.values()) {
                Optional<State> to = ConnectionMachine.next(from, event);
                to.ifPresent(target -> assertThat(guard.get(from)).as(from + " by " + event).contains(target));
            }
        }
    }

    @Provide
    Arbitrary<List<Event>> runs() {
        return Arbitraries.of(Event.values()).list().ofMinSize(0).ofMaxSize(60);
    }

    @Property
    void aConnectionIsNeverSwitchedOnStraightFromUntestedAndOnlyTwoEventsEverLeadToEnabled(
            @ForAll("runs") List<Event> run) {
        State state = State.UNTESTED;
        for (Event event : run) {
            Optional<State> next = ConnectionMachine.next(state, event);
            if (next.isEmpty()) {
                continue;
            }
            if (next.get() == State.ENABLED && state != State.ENABLED) {
                assertThat(state).as("into ENABLED").isIn(State.TESTED, State.DISABLED, State.FAILING);
                assertThat(event).isIn(Event.ENABLE, Event.TEST_PASSED);
            }
            state = next.get();
        }
    }

    @Property
    void aConnectionThatIsOnIsOnlyEverLeftBySwitchingOffOrStaysOn(@ForAll("runs") List<Event> run) {
        State state = State.UNTESTED;
        for (Event event : run) {
            Optional<State> next = ConnectionMachine.next(state, event);
            if (next.isEmpty()) {
                continue;
            }
            if (ConnectionMachine.isOn(state) && !ConnectionMachine.isOn(next.get())) {
                assertThat(event).isEqualTo(Event.DISABLE);
            }
            state = next.get();
        }
    }
}
