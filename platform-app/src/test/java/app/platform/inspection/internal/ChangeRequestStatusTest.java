package app.platform.inspection.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The statuses of a change request and the moves between them (FA5, ADR-0191), with no database. The table here is the
 * same one the database enforces (V067, {@code ChangeRequestGuardIT}): a move the lifecycle lacks is a move nobody can
 * write.
 */
class ChangeRequestStatusTest {

    @Test
    void theMovesAreExactlyTheOnesTheLifecycleAllows() {
        assertThat(movesFrom(ChangeRequestStatus.OPEN)).containsExactlyInAnyOrder(ChangeRequestStatus.ACCEPTED,
                ChangeRequestStatus.DECLINED, ChangeRequestStatus.DONE);
        assertThat(movesFrom(ChangeRequestStatus.ACCEPTED)).containsExactlyInAnyOrder(ChangeRequestStatus.DECLINED,
                ChangeRequestStatus.DONE);
        assertThat(movesFrom(ChangeRequestStatus.DONE)).containsExactly(ChangeRequestStatus.OPEN);
        assertThat(movesFrom(ChangeRequestStatus.DECLINED)).as("declined is final").isEmpty();
    }

    @Test
    void noStatusMovesToItself() {
        for (ChangeRequestStatus status : ChangeRequestStatus.values()) {
            assertThat(status.canMoveTo(status)).as(status.name()).isFalse();
        }
    }

    @Test
    void onlyAnOpenOrAcceptedRequestTakesCommentsOrIsTurnedIntoAVersion() {
        for (ChangeRequestStatus status : ChangeRequestStatus.values()) {
            boolean live = status == ChangeRequestStatus.OPEN || status == ChangeRequestStatus.ACCEPTED;
            assertThat(status.takesComments()).as(status.name()).isEqualTo(live);
            assertThat(status.canBeTurned()).as(status.name()).isEqualTo(live);
        }
    }

    @Test
    void aStatusIsReadFromItsStoredNameAndAnythingElseIsNothing() {
        assertThat(ChangeRequestStatus.of("DONE")).contains(ChangeRequestStatus.DONE);
        assertThat(ChangeRequestStatus.of(" accepted ")).contains(ChangeRequestStatus.ACCEPTED);
        assertThat(ChangeRequestStatus.of("SOMETHING")).isEmpty();
        assertThat(ChangeRequestStatus.of(null)).isEmpty();
        assertThat(ChangeRequestStatus.of("")).isEmpty();
    }

    @Test
    void theSettingsAreCheckedWhenTheyAreRead() {
        assertThat(ChangeRequestProperties.standard().maxCommentsPerRequest()).isEqualTo(50);
        assertThatThrownBy(() -> new ChangeRequestProperties(0, 10, 200, 2_000, 120, 500, 10, 20))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("max-comments-per-request");
        assertThatThrownBy(() -> new ChangeRequestProperties(50, 10, 200, 4_001, 120, 500, 10, 20))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("max-comment-chars");
        assertThatThrownBy(() -> new ChangeRequestProperties(50, 10, 200, 2_000, 201, 500, 10, 20))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("max-title-chars");
    }

    private static Set<ChangeRequestStatus> movesFrom(ChangeRequestStatus from) {
        Set<ChangeRequestStatus> moves = EnumSet.noneOf(ChangeRequestStatus.class);
        for (ChangeRequestStatus to : ChangeRequestStatus.values()) {
            if (from.canMoveTo(to)) {
                moves.add(to);
            }
        }
        return moves;
    }
}
