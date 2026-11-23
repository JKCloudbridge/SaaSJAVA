package app.platform.integration.internal;

import java.util.Optional;

/**
 * The life of a webhook subscription as one pure table (Sprint 32, ADR-0276), the same table the database guard
 * holds, so
 * neither can drift without a test noticing.
 *
 * <p>A subscription starts {@link State#DISABLED}. It is switched on ({@link Event#ENABLE}) only for an address that
 * answered a verification as it stands. While on it is {@link State#ACTIVE}; a delivery it could not make moves it to
 * {@link State#FAILING} ({@link Event#FAIL}), a success moves it back ({@link Event#RECOVER}), and an unbroken
 * failure of
 * the period the settings state makes the platform switch it off ({@link Event#AUTO_DISABLE}). An administrator
 * switches it off at any time ({@link Event#DISABLE}). It is changed (address, events, objects, name) only while it is
 * off; a change keeps it off and makes the last verification void, because the verification belongs to one address.
 *
 * <p>"Stopped because its member can no longer act" is not a state: it is read at the moment of every delivery from the
 * member's standing, so a member who comes back needs no switch.
 */
final class SubscriptionMachine {

    /** Where a subscription is. */
    enum State {
        /** Not sending: a new subscription, one an administrator switched off and one the platform switched off. */
        DISABLED,
        /** Sending. */
        ACTIVE,
        /** Sending, and the latest attempts failed; shown so that it is seen before it is switched off. */
        FAILING
    }

    /** What can happen to a subscription. */
    enum Event {
        /** An administrator switches it on. */
        ENABLE,
        /** An administrator switches it off. */
        DISABLE,
        /** The platform switches it off after the unbroken failure of the whole period. */
        AUTO_DISABLE,
        /** A delivery could not be made. */
        FAIL,
        /** A delivery was accepted after failures. */
        RECOVER
    }

    private SubscriptionMachine() {
    }

    /**
     * The state after the event, or empty when the event does not apply in this state.
     *
     * @param verified whether the address answered a verification as it stands now; switching on needs it
     */
    static Optional<State> next(State state, Event event, boolean verified) {
        return switch (state) {
            case DISABLED -> event == Event.ENABLE && verified ? Optional.of(State.ACTIVE) : Optional.empty();
            case ACTIVE -> switch (event) {
                case FAIL -> Optional.of(State.FAILING);
                case DISABLE, AUTO_DISABLE -> Optional.of(State.DISABLED);
                case ENABLE, RECOVER -> Optional.empty();
            };
            case FAILING -> switch (event) {
                case RECOVER -> Optional.of(State.ACTIVE);
                case DISABLE, AUTO_DISABLE -> Optional.of(State.DISABLED);
                case ENABLE, FAIL -> Optional.empty();
            };
        };
    }

    /** Whether deliveries are made in this state. */
    static boolean sending(State state) {
        return state != State.DISABLED;
    }

    /** Whether what the subscription is may be changed in this state: only while it is switched off. */
    static boolean canChange(State state) {
        return state == State.DISABLED;
    }

    /** The stored reason a subscription is off after the event, or null when the event does not switch it off. */
    static String disabledReason(Event event) {
        return switch (event) {
            case DISABLE -> "switched_off";
            case AUTO_DISABLE -> "auto_disabled";
            default -> null;
        };
    }
}
