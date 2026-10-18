package app.platform.platformadmin.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.platformadmin.internal.TrialEndPolicy.Settings;
import app.platform.platformadmin.internal.TrialEndPolicy.Stage;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;

/**
 * The end of a trial on random settings and random sequences of looks of the job (ADR-0162): whatever the job does with
 * what is due, a stage is never due again once it was noted, the suspension is acted on at most once, no notice comes
 * before the trial is over, and the suspension never comes before the notices.
 */
class TrialEndPolicyPropertyTest {

    private static final Instant ENDS = Instant.parse("2026-11-01T12:00:00Z");

    @Property
    void aStageNotedIsNeverDueAgainAndTheSuspensionHappensAtMostOnce(@ForAll @IntRange(min = 0, max = 60) int grace,
            @ForAll @IntRange(min = 1, max = 30) int first, @ForAll @IntRange(min = 1, max = 30) int last,
            @ForAll @Size(max = 25) List<@IntRange(min = 0, max = 20) Integer> gaps) {
        Settings settings = new Settings(Duration.ofDays(grace), Math.max(first, last), Math.min(first, last));
        Set<String> noted = new HashSet<>();
        int suspensions = 0;
        long hours = 0;
        for (int gap : gaps) {
            hours += gap * 12L;
            List<Stage> due = TrialEndPolicy.due(ENDS.plus(Duration.ofHours(hours)), ENDS, settings, noted);
            due.forEach(stage -> assertThat(noted).doesNotContain(stage.name()));
            if (TrialEndPolicy.toAct(due).filter(stage -> stage == Stage.SUSPENDED).isPresent()) {
                suspensions++;
            }
            due.forEach(stage -> noted.add(stage.name()));
        }
        assertThat(suspensions).isLessThanOrEqualTo(1);
    }

    @Property
    void noNoticeComesBeforeTheEndAndTheSuspensionIsLast(@ForAll @IntRange(min = 0, max = 60) int grace,
            @ForAll @IntRange(min = 1, max = 30) int first, @ForAll @IntRange(min = 1, max = 30) int last) {
        Settings settings = new Settings(Duration.ofDays(grace), Math.max(first, last), Math.min(first, last));

        Instant firstAt = TrialEndPolicy.at(Stage.NOTICE_FIRST, ENDS, settings);
        Instant finalAt = TrialEndPolicy.at(Stage.NOTICE_FINAL, ENDS, settings);
        Instant suspendAt = TrialEndPolicy.at(Stage.SUSPENDED, ENDS, settings);

        assertThat(firstAt).isAfterOrEqualTo(ENDS);
        assertThat(finalAt).isAfterOrEqualTo(firstAt);
        assertThat(suspendAt).isAfterOrEqualTo(finalAt);
    }

    @Property
    void whenEverythingIsDueTheLastStageIsTheSuspensionAndNothingFollows(
            @ForAll @IntRange(min = 0, max = 60) int grace) {
        Settings settings = new Settings(Duration.ofDays(grace), 7, 1);
        Instant late = ENDS.plus(Duration.ofDays(grace + 365L));

        List<Stage> due = TrialEndPolicy.due(late, ENDS, settings, Set.of());

        assertThat(TrialEndPolicy.toAct(due)).contains(Stage.SUSPENDED);
        Set<String> noted = new HashSet<>();
        due.forEach(stage -> noted.add(stage.name()));
        assertThat(TrialEndPolicy.due(late, ENDS, settings, noted)).isEmpty();
    }
}
