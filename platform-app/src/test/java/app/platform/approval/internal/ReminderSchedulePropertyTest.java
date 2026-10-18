package app.platform.approval.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.approval.internal.ReminderSchedule.Event;
import app.platform.approval.internal.ReminderSchedule.Kind;
import app.platform.approval.internal.ReminderSchedule.State;
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
 * "Never twice for the same step and time" (ADR-0159), on random processes and random sequences of looks of the timer:
 * whatever the timer does with what is due, no reminder number, no escalation and no overdue mark is ever due again, no
 * more than three reminders are ever sent, and once the last look is far enough ahead nothing is left to do.
 */
class ReminderSchedulePropertyTest {

    private static final Instant ACTIVE = Instant.parse("2026-10-18T09:00:00Z");

    @Property
    void nothingIsEverDueTwiceAndAtMostThreeRemindersAreSent(@ForAll @IntRange(min = 1, max = 200) int remind,
            @ForAll @IntRange(min = 1, max = 400) int escalate, @ForAll boolean reminds, @ForAll boolean escalates,
            @ForAll @Size(max = 25) List<@IntRange(min = 0, max = 150) Integer> gaps) {
        Integer remindHours = reminds ? remind : null;
        Integer escalateHours = escalates ? escalate : null;
        State state = State.FRESH;
        Set<String> handled = new HashSet<>();
        int sent = 0;
        long hours = 0;
        for (int gap : gaps) {
            hours += gap;
            List<Event> due = ReminderSchedule.due(ACTIVE.plus(Duration.ofHours(hours)), ACTIVE, remindHours,
                    escalateHours, state);
            for (Event event : due) {
                assertThat(handled.add(event.kind() + "/" + event.sequence()))
                        .as("%s was due twice", event).isTrue();
            }
            if (ReminderSchedule.reminderToSend(due) != null) {
                sent++;
            }
            state = ReminderSchedule.after(state, due);
        }
        assertThat(sent).isLessThanOrEqualTo(ReminderSchedule.MAX_REMINDERS);
    }

    @Property
    void afterEverythingIsDueNothingIsPendingAndTheTimerStops(@ForAll @IntRange(min = 1, max = 200) int remind,
            @ForAll @IntRange(min = 1, max = 400) int escalate, @ForAll boolean reminds, @ForAll boolean escalates) {
        Integer remindHours = reminds ? remind : null;
        Integer escalateHours = escalates ? escalate : null;
        Instant late = ACTIVE.plus(Duration.ofHours(10_000));

        List<Event> due = ReminderSchedule.due(late, ACTIVE, remindHours, escalateHours, State.FRESH);
        State state = ReminderSchedule.after(State.FRESH, due);

        assertThat(ReminderSchedule.pending(ACTIVE, remindHours, escalateHours, state)).isEmpty();
        assertThat(ReminderSchedule.next(ACTIVE, remindHours, escalateHours, state)).isNull();
        assertThat(due.stream().filter(event -> event.kind() == Kind.ESCALATION).count())
                .isEqualTo(escalates ? 1 : 0);
        assertThat(due.stream().filter(event -> event.kind() == Kind.OVERDUE).count())
                .isEqualTo(reminds || escalates ? 1 : 0);
    }

    @Property
    void theNextLookIsAlwaysAfterNow(@ForAll @IntRange(min = 1, max = 200) int remind,
            @ForAll @IntRange(min = 0, max = 500) int hours) {
        Instant now = ACTIVE.plus(Duration.ofHours(hours));
        List<Event> due = ReminderSchedule.due(now, ACTIVE, remind, null, State.FRESH);
        State state = ReminderSchedule.after(State.FRESH, due);

        Instant next = ReminderSchedule.next(ACTIVE, remind, null, state);

        if (next != null) {
            assertThat(next).isAfter(now);
        }
    }
}
