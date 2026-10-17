package app.platform.workflow.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.platform.metadata.WorkflowDefinition;
import app.platform.metadata.WorkflowDefinition.Kind;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * When a scheduled trigger is next due (ADR-0134), without a database or a clock: a time of day is a wall-clock time
 * once per local date, so a change of the clock (spring forward, fall back) and a change of the zone never make a
 * workflow run twice in a day or not at all, and a time that was missed is made up once and not in a burst.
 */
class ScheduleMathTest {

    private static final ZoneId UTC = ZoneId.of("UTC");
    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    private static WorkflowDefinition.Trigger daily(ZoneId zone, String time) {
        return new WorkflowDefinition.Trigger(Kind.EVERY_DAY, null, null, null, zone, LocalTime.parse(time), null,
                null, null);
    }

    private static WorkflowDefinition.Trigger weekly(ZoneId zone, DayOfWeek day, String time) {
        return new WorkflowDefinition.Trigger(Kind.EVERY_WEEK, null, null, null, zone, LocalTime.parse(time), day,
                null, null);
    }

    private static WorkflowDefinition.Trigger once(ZoneId zone, String at) {
        return new WorkflowDefinition.Trigger(Kind.DATE_TIME, null, null, null, zone, null, null,
                LocalDateTime.parse(at), null);
    }

    private static Instant local(ZoneId zone, String dateTime) {
        return LocalDateTime.parse(dateTime).atZone(zone).toInstant();
    }

    // ---- every day ----

    @Test
    void aDailyTriggerIsDueLaterTodayWhenTheTimeHasNotCome() {
        Instant next = ScheduleMath.next(daily(UTC, "09:00"), local(UTC, "2026-10-16T08:00")).orElseThrow();

        assertThat(next).isEqualTo(local(UTC, "2026-10-16T09:00"));
    }

    @Test
    void aDailyTriggerIsDueTomorrowWhenTheTimeHasPassedAndExactlyNowIsNotDueAgain() {
        WorkflowDefinition.Trigger trigger = daily(UTC, "09:00");

        assertThat(ScheduleMath.next(trigger, local(UTC, "2026-10-16T09:00"))).contains(local(UTC, "2026-10-17T09:00"));
        assertThat(ScheduleMath.next(trigger, local(UTC, "2026-10-16T23:59"))).contains(local(UTC, "2026-10-17T09:00"));
    }

    @Test
    void theWallClockOfTheZoneDecidesNotTheUtcDate() {
        // 23:30 in New York is already the next day in UTC: the occurrence belongs to the New York date.
        Instant after = local(NEW_YORK, "2026-10-16T23:30");

        assertThat(ScheduleMath.next(daily(NEW_YORK, "23:45"), after)).contains(local(NEW_YORK, "2026-10-16T23:45"));
        assertThat(ScheduleMath.next(daily(NEW_YORK, "00:15"), after)).contains(local(NEW_YORK, "2026-10-17T00:15"));
    }

    @Test
    void whenTheClockSkipsTheTimeTheOccurrenceMovesForwardAndTheDayStillHasExactlyOne() {
        // Berlin, 2026-03-29: 02:00 became 03:00, so 02:30 does not exist.
        WorkflowDefinition.Trigger trigger = daily(BERLIN, "02:30");
        Instant before = local(BERLIN, "2026-03-28T12:00");

        Instant onTheDay = ScheduleMath.next(trigger, before).orElseThrow();
        Instant following = ScheduleMath.next(trigger, onTheDay).orElseThrow();

        assertThat(onTheDay.atZone(BERLIN).toLocalDate()).isEqualTo(LocalDate.parse("2026-03-29"));
        assertThat(onTheDay.atZone(BERLIN).toLocalTime()).as("moved forward by the gap").isEqualTo(LocalTime.of(3, 30));
        assertThat(following.atZone(BERLIN).toLocalDate()).isEqualTo(LocalDate.parse("2026-03-30"));
        assertThat(following.atZone(BERLIN).toLocalTime()).isEqualTo(LocalTime.of(2, 30));
    }

    @Test
    void whenTheClockRepeatsTheTimeTheEarlierOccurrenceIsUsedAndTheDayStillHasExactlyOne() {
        // Berlin, 2026-10-25: 03:00 became 02:00, so 02:30 happens twice.
        WorkflowDefinition.Trigger trigger = daily(BERLIN, "02:30");
        Instant before = local(BERLIN, "2026-10-24T12:00");

        Instant onTheDay = ScheduleMath.next(trigger, before).orElseThrow();
        Instant following = ScheduleMath.next(trigger, onTheDay).orElseThrow();

        assertThat(onTheDay.atZone(BERLIN).toLocalDate()).isEqualTo(LocalDate.parse("2026-10-25"));
        assertThat(onTheDay).as("the earlier of the two 02:30").isEqualTo(
                ZonedDateTime.of(LocalDate.parse("2026-10-25"), LocalTime.of(2, 30), BERLIN)
                        .withEarlierOffsetAtOverlap().toInstant());
        assertThat(following.atZone(BERLIN).toLocalDate()).as("not the second 02:30 of the same day")
                .isEqualTo(LocalDate.parse("2026-10-26"));
    }

    @Test
    void aChangedZoneGivesTheNextOccurrenceInTheNewZone() {
        Instant after = local(UTC, "2026-10-16T06:00");

        assertThat(ScheduleMath.next(daily(UTC, "09:00"), after)).contains(local(UTC, "2026-10-16T09:00"));
        assertThat(ScheduleMath.next(daily(BERLIN, "09:00"), after)).contains(local(BERLIN, "2026-10-16T09:00"));
        assertThat(local(BERLIN, "2026-10-16T09:00")).isBefore(local(UTC, "2026-10-16T09:00"));
    }

    // ---- every week ----

    @Test
    void aWeeklyTriggerIsDueOnItsDayAndNeverTwiceInAWeek() {
        WorkflowDefinition.Trigger trigger = weekly(UTC, DayOfWeek.MONDAY, "09:00");
        Instant wednesday = local(UTC, "2026-10-14T12:00");

        Instant monday = ScheduleMath.next(trigger, wednesday).orElseThrow();
        Instant following = ScheduleMath.next(trigger, monday).orElseThrow();

        assertThat(monday.atZone(UTC).getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
        assertThat(monday).isEqualTo(local(UTC, "2026-10-19T09:00"));
        assertThat(following).isEqualTo(local(UTC, "2026-10-26T09:00"));
    }

    @Test
    void aWeeklyTriggerOnItsDayBeforeTheTimeIsDueThatSameDay() {
        assertThat(ScheduleMath.next(weekly(UTC, DayOfWeek.MONDAY, "09:00"), local(UTC, "2026-10-19T07:00")))
                .contains(local(UTC, "2026-10-19T09:00"));
    }

    // ---- once ----

    @Test
    void aOneTimeMomentIsDueOnceAndThenNever() {
        WorkflowDefinition.Trigger trigger = once(BERLIN, "2026-12-31T09:30");

        assertThat(ScheduleMath.next(trigger, local(UTC, "2026-10-16T00:00")))
                .contains(local(BERLIN, "2026-12-31T09:30"));
        assertThat(ScheduleMath.next(trigger, local(BERLIN, "2026-12-31T09:30"))).isEqualTo(Optional.empty());
        assertThat(ScheduleMath.next(trigger, local(BERLIN, "2027-01-01T00:00"))).isEmpty();
    }

    // ---- days after a date runs daily at its time ----

    @Test
    void theDaysAfterTriggerIsDueEveryDayAtItsTime() {
        WorkflowDefinition.Trigger trigger = new WorkflowDefinition.Trigger(Kind.DAYS_AFTER_DATE, null, "closeDate__c",
                30, UTC, LocalTime.of(6, 0), null, null, null);

        assertThat(ScheduleMath.next(trigger, local(UTC, "2026-10-16T07:00"))).contains(local(UTC, "2026-10-17T06:00"));
    }

    // ---- a missed time is not made up in a burst ----

    @Test
    void afterDowntimeTheLatestDueOccurrenceRunsOnceAndTheEarlierOnesAreSkipped() {
        WorkflowDefinition.Trigger trigger = daily(UTC, "09:00");
        Instant due = local(UTC, "2026-10-12T09:00");
        Instant now = local(UTC, "2026-10-16T10:00");

        assertThat(ScheduleMath.latestDue(trigger, due, now)).isEqualTo(local(UTC, "2026-10-16T09:00"));
    }

    @Test
    void whenNothingWasMissedTheDueOccurrenceIsTheOneThatRuns() {
        WorkflowDefinition.Trigger trigger = daily(UTC, "09:00");
        Instant due = local(UTC, "2026-10-16T09:00");

        assertThat(ScheduleMath.latestDue(trigger, due, local(UTC, "2026-10-16T09:00")))
                .isEqualTo(due);
        assertThat(ScheduleMath.latestDue(trigger, due, local(UTC, "2026-10-16T20:00"))).isEqualTo(due);
    }

    @Test
    void aOneTimeMomentIsNeverSkippedForALaterOne() {
        WorkflowDefinition.Trigger trigger = once(UTC, "2026-10-12T09:00");
        Instant due = local(UTC, "2026-10-12T09:00");

        assertThat(ScheduleMath.latestDue(trigger, due, local(UTC, "2027-01-01T00:00"))).isEqualTo(due);
    }

    @Test
    void aStepFromAnInstantIsAlwaysLaterThanTheInstant() {
        WorkflowDefinition.Trigger trigger = daily(BERLIN, "02:30");
        Instant at = local(BERLIN, "2026-01-01T00:00");

        for (int step = 0; step < 800; step++) {
            Instant next = ScheduleMath.next(trigger, at).orElseThrow();
            assertThat(next).isAfter(at);
            at = next;
        }
    }

    @Test
    void aTriggerThatIsNotScheduledIsRefused() {
        WorkflowDefinition.Trigger record = new WorkflowDefinition.Trigger(Kind.RECORD_CREATED, null, null, null, null,
                null, null, null, null);

        assertThatThrownBy(() -> ScheduleMath.next(record, Instant.now())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theStartOfADayIsTheFirstMomentOfTheLocalDate() {
        assertThat(ScheduleMath.startOfDay(LocalDate.parse("2026-10-16"), BERLIN))
                .isEqualTo(local(BERLIN, "2026-10-16T00:00"));
    }
}
