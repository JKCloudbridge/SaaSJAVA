package app.platform.approval.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * When an active step of an approval is reminded, escalated and marked overdue (Sprint 25, ADR-0159): the arithmetic of
 * the timer, as a pure function of the moment the step became active, the two durations of the process and what was
 * done so far. No database, no clock, no I/O, so the rule "never twice for the same step and time" is proved by a
 * property test.
 *
 * <p>The events of a step, in the order of their time:
 * <ul>
 *   <li><strong>reminders</strong>, at {@code n x remindAfter} after the step became active, for n = 1 to
 *       {@link #MAX_REMINDERS};</li>
 *   <li><strong>one escalation</strong> at {@code escalateAfter}: the fallback approver is asked as well;</li>
 *   <li><strong>the overdue mark</strong> once the reminders are used up ({@code (MAX_REMINDERS + 1) x remindAfter}),
 *       or, for a process that only escalates, at the escalation.</li>
 * </ul>
 * Each event happens once: the state carries how many reminders were sent and whether the escalation and the mark were
 * made, and {@link #pending} lists only what has not happened.
 */
final class ReminderSchedule {

    /** Most reminders of one step (ADR-0159); after the last one comes the overdue mark. */
    static final int MAX_REMINDERS = 3;

    /** The kinds of event, in the order they take when they fall at the same moment. */
    enum Kind {
        /** The people asked are reminded. */
        REMINDER,
        /** The fallback approver is asked as well. */
        ESCALATION,
        /** The request is marked overdue. */
        OVERDUE
    }

    /**
     * One thing the timer does to a step.
     *
     * @param kind what
     * @param sequence the number of the reminder (from 1), 1 for the others
     * @param at when it falls due
     */
    record Event(Kind kind, int sequence, Instant at) {
    }

    /**
     * What was done so far to a step.
     *
     * @param remindersSent how many reminders were sent or passed over
     * @param escalated whether the escalation was made
     * @param overdue whether the overdue mark was made
     */
    record State(int remindersSent, boolean escalated, boolean overdue) {

        static final State FRESH = new State(0, false, false);
    }

    private ReminderSchedule() {
    }

    /** Whether a process uses the timer at all. */
    static boolean used(Integer remindAfterHours, Integer escalateAfterHours) {
        return remindAfterHours != null || escalateAfterHours != null;
    }

    /** Every event that has not happened yet, in the order of its time. */
    static List<Event> pending(Instant activatedAt, Integer remindAfterHours, Integer escalateAfterHours,
            State done) {
        List<Event> events = new ArrayList<>();
        if (remindAfterHours != null) {
            Duration every = Duration.ofHours(remindAfterHours);
            for (int n = done.remindersSent() + 1; n <= MAX_REMINDERS; n++) {
                events.add(new Event(Kind.REMINDER, n, activatedAt.plus(every.multipliedBy(n))));
            }
            if (!done.overdue()) {
                events.add(new Event(Kind.OVERDUE, 1, activatedAt.plus(every.multipliedBy(MAX_REMINDERS + 1L))));
            }
        }
        if (escalateAfterHours != null) {
            Instant at = activatedAt.plus(Duration.ofHours(escalateAfterHours));
            if (!done.escalated()) {
                events.add(new Event(Kind.ESCALATION, 1, at));
            }
            if (remindAfterHours == null && !done.overdue()) {
                events.add(new Event(Kind.OVERDUE, 1, at));
            }
        }
        events.sort(Comparator.comparing(Event::at).thenComparing(Event::kind).thenComparingInt(Event::sequence));
        return events;
    }

    /** The events that are due at {@code now}, in the order of their time. */
    static List<Event> due(Instant now, Instant activatedAt, Integer remindAfterHours, Integer escalateAfterHours,
            State done) {
        return pending(activatedAt, remindAfterHours, escalateAfterHours, done).stream()
                .filter(event -> !event.at().isAfter(now)).toList();
    }

    /** When the timer should look at the step next, or null when nothing remains. */
    static Instant next(Instant activatedAt, Integer remindAfterHours, Integer escalateAfterHours, State done) {
        List<Event> pending = pending(activatedAt, remindAfterHours, escalateAfterHours, done);
        return pending.isEmpty() ? null : pending.get(0).at();
    }

    /**
     * The state after the due events were handled. Of several reminders that fell due together (the timer was down),
     * only the last is sent; the earlier ones are passed over, so a person is never sent a burst. All of them count as
     * done, so none returns.
     */
    static State after(State before, List<Event> handled) {
        int reminders = before.remindersSent();
        boolean escalated = before.escalated();
        boolean overdue = before.overdue();
        for (Event event : handled) {
            switch (event.kind()) {
                case REMINDER -> reminders = Math.max(reminders, event.sequence());
                case ESCALATION -> escalated = true;
                case OVERDUE -> overdue = true;
            }
        }
        return new State(reminders, escalated, overdue);
    }

    /** The reminder to send among the due events: the latest one, or null when none is due. */
    static Event reminderToSend(List<Event> due) {
        Event latest = null;
        for (Event event : due) {
            if (event.kind() == Kind.REMINDER && (latest == null || event.sequence() > latest.sequence())) {
                latest = event;
            }
        }
        return latest;
    }
}
