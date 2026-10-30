package app.platform.integration.internal;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/**
 * The life of a connection as one pure table (ADR-0210). The database holds the same table in
 * {@code platform_integration_connection_guard} (V083), so a mistake here cannot leave a connection in a state the
 * database refuses to know.
 *
 * <p>Switched on means {@code ENABLED} or {@code FAILING}. Only {@code ENABLED} is usable. A connection that is on is
 * frozen: it is switched off to be changed or removed. {@code ENABLED} always rests on a successful test of exactly
 * the current definition; that part is a fact about fingerprints and is checked by the caller and by the database.
 */
final class ConnectionMachine {

    /** Where a connection stands. */
    enum State {
        /** Made or changed, no successful test of the current definition. */
        UNTESTED("Not tested"),
        /** The current definition was tested successfully; not switched on. */
        TESTED("Tested, switched off"),
        /** Switched on and working. The only state that may be used. */
        ENABLED("On"),
        /** Switched off by a person. */
        DISABLED("Off"),
        /** Switched on, but the last test or use failed. Not usable until a test passes or it is switched off. */
        FAILING("On, but failing");

        private final String label;

        State(String label) {
            this.label = label;
        }

        /** What a person sees. */
        String label() {
            return label;
        }
    }

    /** What can happen to a connection. */
    enum Event {
        /** A change of the address, the authentication or the settings. */
        EDIT,
        /** A test of the current definition passed. */
        TEST_PASSED,
        /** A test of the current definition failed. */
        TEST_FAILED,
        /** A person switches it on. */
        ENABLE,
        /** A person switches it off. */
        DISABLE,
        /** A use of the connection failed (reported by the sprints that use it). */
        REPORT_FAILURE
    }

    private static final Map<State, Map<Event, State>> MOVES = new EnumMap<>(State.class);

    static {
        MOVES.put(State.UNTESTED, Map.of(
                Event.EDIT, State.UNTESTED,
                Event.TEST_PASSED, State.TESTED,
                Event.TEST_FAILED, State.UNTESTED));
        MOVES.put(State.TESTED, Map.of(
                Event.EDIT, State.UNTESTED,
                Event.TEST_PASSED, State.TESTED,
                Event.TEST_FAILED, State.UNTESTED,
                Event.ENABLE, State.ENABLED));
        MOVES.put(State.ENABLED, Map.of(
                Event.TEST_PASSED, State.ENABLED,
                Event.TEST_FAILED, State.FAILING,
                Event.DISABLE, State.DISABLED,
                Event.REPORT_FAILURE, State.FAILING));
        MOVES.put(State.FAILING, Map.of(
                Event.TEST_PASSED, State.ENABLED,
                Event.TEST_FAILED, State.FAILING,
                Event.DISABLE, State.DISABLED,
                Event.REPORT_FAILURE, State.FAILING));
        MOVES.put(State.DISABLED, Map.of(
                Event.EDIT, State.UNTESTED,
                Event.TEST_PASSED, State.DISABLED,
                Event.TEST_FAILED, State.DISABLED,
                Event.ENABLE, State.ENABLED));
    }

    private ConnectionMachine() {
    }

    /** The state after the event, or nothing when the event is not allowed in this state. */
    static Optional<State> next(State state, Event event) {
        return Optional.ofNullable(MOVES.get(state).get(event));
    }

    /** Switched on: it is meant to be working, whether or not it is right now. */
    static boolean isOn(State state) {
        return state == State.ENABLED || state == State.FAILING;
    }

    /** Whether the platform may use the connection. Disabling takes effect at the next question: nothing is cached. */
    static boolean isUsable(State state) {
        return state == State.ENABLED;
    }

    /** Whether the definition can be changed or the connection removed: only while it is not on. */
    static boolean canChange(State state) {
        return !isOn(state);
    }

    /** Whether the first move of a switch-on is possible from this state (the test fact is checked apart). */
    static boolean mayEnable(State state) {
        return next(state, Event.ENABLE).isPresent();
    }
}
