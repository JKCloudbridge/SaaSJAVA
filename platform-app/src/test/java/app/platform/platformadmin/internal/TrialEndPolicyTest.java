package app.platform.platformadmin.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.platform.platformadmin.internal.TrialEndPolicy.Settings;
import app.platform.platformadmin.internal.TrialEndPolicy.Stage;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TrialEndPolicyTest {

    private static final Instant ENDS = Instant.parse("2026-11-01T12:00:00Z");
    private static final Settings DEFAULTS = new Settings(Duration.ofDays(7), 7, 1);

    private static Instant days(long days) {
        return ENDS.plus(Duration.ofDays(days));
    }

    @Test
    void theSuspensionFallsAtTheEndOfTheTrialPlusTheGrace() {
        assertThat(TrialEndPolicy.suspendAt(ENDS, DEFAULTS)).isEqualTo(days(7));
    }

    @Test
    void withTheDefaultsTheFirstNoticeComesWhenTheTrialEndsAndTheFinalOneADayBeforeTheSuspension() {
        assertThat(TrialEndPolicy.at(Stage.NOTICE_FIRST, ENDS, DEFAULTS)).isEqualTo(ENDS);
        assertThat(TrialEndPolicy.at(Stage.NOTICE_FINAL, ENDS, DEFAULTS)).isEqualTo(days(6));
        assertThat(TrialEndPolicy.at(Stage.SUSPENDED, ENDS, DEFAULTS)).isEqualTo(days(7));
    }

    @Test
    void aNoticeNeverComesBeforeTheTrialIsOver() {
        Settings shortGrace = new Settings(Duration.ofDays(2), 7, 1);

        assertThat(TrialEndPolicy.at(Stage.NOTICE_FIRST, ENDS, shortGrace)).isEqualTo(ENDS);
        assertThat(TrialEndPolicy.at(Stage.NOTICE_FINAL, ENDS, shortGrace)).isEqualTo(days(1));
        assertThat(TrialEndPolicy.at(Stage.SUSPENDED, ENDS, shortGrace)).isEqualTo(days(2));
    }

    @Test
    void nothingIsDueWhileTheTrialRuns() {
        assertThat(TrialEndPolicy.due(ENDS.minusSeconds(1), ENDS, DEFAULTS, Set.of())).isEmpty();
    }

    @Test
    void theStagesComeDueInOrderAndOnlyOnceNotedStagesStayOut() {
        assertThat(TrialEndPolicy.due(ENDS, ENDS, DEFAULTS, Set.of())).containsExactly(Stage.NOTICE_FIRST);
        assertThat(TrialEndPolicy.due(days(6), ENDS, DEFAULTS, Set.of("NOTICE_FIRST")))
                .containsExactly(Stage.NOTICE_FINAL);
        assertThat(TrialEndPolicy.due(days(7), ENDS, DEFAULTS, Set.of("NOTICE_FIRST", "NOTICE_FINAL")))
                .containsExactly(Stage.SUSPENDED);
        assertThat(TrialEndPolicy.due(days(30), ENDS, DEFAULTS, Set.of("NOTICE_FIRST", "NOTICE_FINAL", "SUSPENDED")))
                .isEmpty();
    }

    @Test
    void ifTheJobWasDownOnlyTheLatestDueStageIsActedOn() {
        List<Stage> due = TrialEndPolicy.due(days(10), ENDS, DEFAULTS, Set.of());

        assertThat(due).containsExactly(Stage.NOTICE_FIRST, Stage.NOTICE_FINAL, Stage.SUSPENDED);
        assertThat(TrialEndPolicy.toAct(due)).contains(Stage.SUSPENDED);
        assertThat(TrialEndPolicy.toAct(List.of(Stage.NOTICE_FIRST, Stage.NOTICE_FINAL))).contains(Stage.NOTICE_FINAL);
        assertThat(TrialEndPolicy.toAct(List.of())).isEqualTo(Optional.empty());
    }

    @Test
    void withNoGraceTheOrganizationIsSuspendedAtOnceAndTheNoticesAreOnlyNoted() {
        Settings none = new Settings(Duration.ZERO, 7, 1);

        List<Stage> due = TrialEndPolicy.due(ENDS, ENDS, none, Set.of());

        assertThat(due).containsExactly(Stage.NOTICE_FIRST, Stage.NOTICE_FINAL, Stage.SUSPENDED);
        assertThat(TrialEndPolicy.toAct(due)).contains(Stage.SUSPENDED);
    }

    @Test
    void anExtendedTrialHasANewEndAndItsStagesStartAgain() {
        Instant extended = days(30);

        assertThat(TrialEndPolicy.due(days(31), extended, DEFAULTS, Set.of())).containsExactly(Stage.NOTICE_FIRST);
    }

    @Test
    void daysLeftAreRoundedUpAndAtLeastOne() {
        assertThat(TrialEndPolicy.daysLeft(ENDS, ENDS, DEFAULTS)).isEqualTo(7);
        assertThat(TrialEndPolicy.daysLeft(days(6), ENDS, DEFAULTS)).isEqualTo(1);
        assertThat(TrialEndPolicy.daysLeft(days(6).plus(Duration.ofHours(23)), ENDS, DEFAULTS)).isEqualTo(1);
        assertThat(TrialEndPolicy.daysLeft(days(9), ENDS, DEFAULTS)).isEqualTo(1);
    }

    @Test
    void theSettingsAreChecked() {
        assertThatThrownBy(() -> new Settings(Duration.ofDays(-1), 7, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Settings(Duration.ofDays(7), 0, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Settings(Duration.ofDays(7), 1, 2)).isInstanceOf(IllegalArgumentException.class);
    }
}
