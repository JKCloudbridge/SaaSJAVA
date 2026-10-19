package app.platform.approval.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.approval.internal.ReminderSchedule.Event;
import app.platform.approval.internal.ReminderSchedule.Kind;
import app.platform.approval.internal.ReminderSchedule.State;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReminderScheduleTest {

    private static final Instant ACTIVE = Instant.parse("2026-10-18T09:00:00Z");

    private static Instant after(long hours) {
        return ACTIVE.plus(Duration.ofHours(hours));
    }

    @Test
    void aProcessWithNoTimesUsesNoTimer() {
        assertThat(ReminderSchedule.used(null, null)).isFalse();
        assertThat(ReminderSchedule.used(24, null)).isTrue();
        assertThat(ReminderSchedule.used(null, 48)).isTrue();
        assertThat(ReminderSchedule.pending(ACTIVE, null, null, State.FRESH)).isEmpty();
        assertThat(ReminderSchedule.next(ACTIVE, null, null, State.FRESH)).isNull();
    }

    @Test
    void remindersComeEveryIntervalThreeTimesThenTheOverdueMark() {
        List<Event> pending = ReminderSchedule.pending(ACTIVE, 24, null, State.FRESH);

        assertThat(pending).containsExactly(new Event(Kind.REMINDER, 1, after(24)),
                new Event(Kind.REMINDER, 2, after(48)), new Event(Kind.REMINDER, 3, after(72)),
                new Event(Kind.OVERDUE, 1, after(96)));
        assertThat(ReminderSchedule.next(ACTIVE, 24, null, State.FRESH)).isEqualTo(after(24));
    }

    @Test
    void theEscalationHappensOnceAtItsTimeAndAProcessThatOnlyEscalatesIsOverdueThen() {
        List<Event> pending = ReminderSchedule.pending(ACTIVE, null, 48, State.FRESH);

        assertThat(pending).containsExactly(new Event(Kind.ESCALATION, 1, after(48)),
                new Event(Kind.OVERDUE, 1, after(48)));
    }

    @Test
    void remindingAndEscalatingTogetherAreOrderedByTime() {
        List<Event> pending = ReminderSchedule.pending(ACTIVE, 24, 36, State.FRESH);

        assertThat(pending.stream().map(Event::kind)).containsExactly(Kind.REMINDER, Kind.ESCALATION, Kind.REMINDER,
                Kind.REMINDER, Kind.OVERDUE);
    }

    @Test
    void nothingIsDueBeforeItsTimeAndEverythingUpToNowIsDueAfter() {
        assertThat(ReminderSchedule.due(after(23), ACTIVE, 24, null, State.FRESH)).isEmpty();
        assertThat(ReminderSchedule.due(after(24), ACTIVE, 24, null, State.FRESH)).hasSize(1);
        assertThat(ReminderSchedule.due(after(100), ACTIVE, 24, null, State.FRESH)).hasSize(4);
    }

    @Test
    void whatHappenedIsNeverPendingAgain() {
        State done = new State(2, true, false);

        List<Event> pending = ReminderSchedule.pending(ACTIVE, 24, 36, done);

        assertThat(pending).containsExactly(new Event(Kind.REMINDER, 3, after(72)),
                new Event(Kind.OVERDUE, 1, after(96)));
        assertThat(ReminderSchedule.next(ACTIVE, 24, 36, new State(3, true, true))).isNull();
    }

    @Test
    void ofSeveralRemindersDueTogetherOnlyTheLastIsSentAndAllCountAsDone() {
        List<Event> due = ReminderSchedule.due(after(60), ACTIVE, 24, null, State.FRESH);

        assertThat(due).hasSize(2);
        assertThat(ReminderSchedule.reminderToSend(due)).isEqualTo(new Event(Kind.REMINDER, 2, after(48)));
        State state = ReminderSchedule.after(State.FRESH, due);
        assertThat(state).isEqualTo(new State(2, false, false));
        assertThat(ReminderSchedule.due(after(60), ACTIVE, 24, null, state)).isEmpty();
    }

    @Test
    void noReminderIsToBeSentWhenNoneIsDue() {
        assertThat(ReminderSchedule.reminderToSend(ReminderSchedule.due(after(1), ACTIVE, 24, 30, State.FRESH)))
                .isNull();
        assertThat(ReminderSchedule.reminderToSend(ReminderSchedule.due(after(31), ACTIVE, 24, 30, State.FRESH)))
                .isEqualTo(new Event(Kind.REMINDER, 1, after(24)));
    }

    @Test
    void theMostRemindersIsThree() {
        assertThat(ReminderSchedule.MAX_REMINDERS).isEqualTo(3);
        assertThat(ReminderSchedule.pending(ACTIVE, 1, null, State.FRESH).stream()
                .filter(event -> event.kind() == Kind.REMINDER)).hasSize(3);
    }
}
