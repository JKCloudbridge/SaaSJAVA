package app.platform.integration.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.integration.internal.SubscriptionMachine.Event;
import app.platform.integration.internal.SubscriptionMachine.State;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.Size;
import org.junit.jupiter.api.Test;

/**
 * The life of a webhook subscription (Sprint 32, ADR-0276): the table of moves, the rule that nothing is switched on
 * without a verification, and that the database guard says the same.
 */
class SubscriptionMachineTest {

    @Test
    void aSubscriptionIsSwitchedOnOnlyWhenItsAddressAnswered() {
        assertThat(SubscriptionMachine.next(State.DISABLED, Event.ENABLE, true)).contains(State.ACTIVE);
        assertThat(SubscriptionMachine.next(State.DISABLED, Event.ENABLE, false)).isEmpty();
    }

    @Test
    void aFailureMakesAnActiveSubscriptionFailingAndASuccessMakesItActiveAgain() {
        assertThat(SubscriptionMachine.next(State.ACTIVE, Event.FAIL, true)).contains(State.FAILING);
        assertThat(SubscriptionMachine.next(State.FAILING, Event.RECOVER, true)).contains(State.ACTIVE);
        assertThat(SubscriptionMachine.next(State.FAILING, Event.FAIL, true)).isEmpty();
        assertThat(SubscriptionMachine.next(State.ACTIVE, Event.RECOVER, true)).isEmpty();
    }

    @Test
    void anAdministratorOrThePlatformSwitchesOffFromEitherOnState() {
        for (State on : List.of(State.ACTIVE, State.FAILING)) {
            assertThat(SubscriptionMachine.next(on, Event.DISABLE, true)).contains(State.DISABLED);
            assertThat(SubscriptionMachine.next(on, Event.AUTO_DISABLE, true)).contains(State.DISABLED);
        }
        assertThat(SubscriptionMachine.next(State.DISABLED, Event.DISABLE, true)).isEmpty();
        assertThat(SubscriptionMachine.next(State.DISABLED, Event.AUTO_DISABLE, true)).isEmpty();
    }

    @Test
    void aSwitchedOffSubscriptionNeverBecomesFailingAndOnlyASwitchedOffOneMayBeChanged() {
        for (Event event : Event.values()) {
            assertThat(SubscriptionMachine.next(State.DISABLED, event, true)).isNotEqualTo(Optional.of(State.FAILING));
        }
        assertThat(SubscriptionMachine.canChange(State.DISABLED)).isTrue();
        assertThat(SubscriptionMachine.canChange(State.ACTIVE)).isFalse();
        assertThat(SubscriptionMachine.canChange(State.FAILING)).isFalse();
        assertThat(SubscriptionMachine.sending(State.DISABLED)).isFalse();
        assertThat(SubscriptionMachine.sending(State.ACTIVE)).isTrue();
        assertThat(SubscriptionMachine.sending(State.FAILING)).isTrue();
    }

    @Test
    void theReasonStoredAfterASwitchOffSaysWhoDidIt() {
        assertThat(SubscriptionMachine.disabledReason(Event.DISABLE)).isEqualTo("switched_off");
        assertThat(SubscriptionMachine.disabledReason(Event.AUTO_DISABLE)).isEqualTo("auto_disabled");
        assertThat(SubscriptionMachine.disabledReason(Event.FAIL)).isNull();
    }

    @Property
    void noSequenceOfEventsSwitchesOnAnUnverifiedSubscription(@ForAll @Size(max = 40) List<Event> events,
            @ForAll boolean verified) {
        State state = State.DISABLED;
        boolean everOn = false;
        for (Event event : events) {
            Optional<State> next = SubscriptionMachine.next(state, event, verified);
            if (next.isPresent()) {
                state = next.get();
            }
            everOn |= SubscriptionMachine.sending(state);
        }

        assertThat(everOn && !verified).as("on without a verification").isFalse();
    }

    @Test
    void theDatabaseGuardHoldsTheSameMovesAsThePureTable() throws IOException {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/migration/V096__create_webhooks_and_rate_limit_overrides.sql"));

        assertThat(sql).contains("(old.state = 'DISABLED' and new.state = 'ACTIVE')")
                .contains("(old.state = 'ACTIVE' and new.state in ('FAILING', 'DISABLED'))")
                .contains("(old.state = 'FAILING' and new.state in ('ACTIVE', 'DISABLED'))");
        // Every move the table allows is one of the three the guard names.
        for (State from : State.values()) {
            for (Event event : Event.values()) {
                SubscriptionMachine.next(from, event, true).ifPresent(to -> assertThat(
                        List.of("DISABLED>ACTIVE", "ACTIVE>FAILING", "ACTIVE>DISABLED", "FAILING>ACTIVE",
                                "FAILING>DISABLED")).contains(from + ">" + to));
            }
        }
    }
}
