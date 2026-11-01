package app.platform.integration.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The life of a delivery as one table (Sprint 29, ADR-0230), the table the database guard holds. */
class DeliveryStateTest {

    @Test
    void theOpenStatesAreTheThreeThatStillHaveWorkToDo() {
        assertThat(EnumSet.allOf(DeliveryState.class).stream().filter(DeliveryState::open).toList())
                .containsExactlyInAnyOrder(DeliveryState.PENDING, DeliveryState.SENDING,
                        DeliveryState.WAITING_REMOTE);
    }

    @Test
    void aFinishedDeliveryMovesNowhere() {
        for (DeliveryState state : Set.of(DeliveryState.DONE, DeliveryState.FAILED, DeliveryState.SKIPPED,
                DeliveryState.SUPERSEDED, DeliveryState.DEAD_LETTERED)) {
            assertThat(state.terminal()).isTrue();
            for (DeliveryState next : DeliveryState.values()) {
                assertThat(state.mayMoveTo(next)).as("%s to %s", state, next).isFalse();
            }
        }
    }

    @Test
    void onlyADeadLetterCanBeReplayed() {
        for (DeliveryState state : DeliveryState.values()) {
            assertThat(DeliveryState.mayReplay(state)).isEqualTo(state == DeliveryState.DEAD_LETTERED);
        }
    }

    @Test
    void aDeliveryThatWaitsForTheOtherSideIsAskedAboutNeverSentAgain() {
        assertThat(DeliveryState.WAITING_REMOTE.mayMoveTo(DeliveryState.SENDING)).isFalse();
        assertThat(DeliveryState.WAITING_REMOTE.mayMoveTo(DeliveryState.PENDING)).isFalse();
        assertThat(DeliveryState.WAITING_REMOTE.mayMoveTo(DeliveryState.DONE)).isTrue();
        assertThat(DeliveryState.WAITING_REMOTE.mayMoveTo(DeliveryState.DEAD_LETTERED)).isTrue();
    }

    @Test
    void aQueuedOrHeldDeliveryMayWaitAgainBeSentOrEnd() {
        for (DeliveryState to : DeliveryState.values()) {
            assertThat(DeliveryState.PENDING.mayMoveTo(to)).isTrue();
            assertThat(DeliveryState.SENDING.mayMoveTo(to)).isTrue();
        }
    }

    @Test
    void theOpenListIsWhatTheStatementsWrite() {
        assertThat(DeliveryState.openList()).isEqualTo("'PENDING', 'SENDING', 'WAITING_REMOTE'");
    }
}
