package app.platform.workflow.internal;

import app.platform.metadata.WorkflowDefinition;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;

/**
 * When a scheduled trigger is next due (ADR-0135, ADR-0136). Pure functions of the trigger and a moment: no database,
 * no clock, so every case of a daylight-saving change or a changed zone is a unit test.
 *
 * <p><strong>Local time rules.</strong> A time of day is a wall-clock time in the zone of the definition, once per
 * local date. When the clock skips the time (spring forward) the occurrence moves forward by the length of the gap,
 * as
 * {@link ZonedDateTime#of} resolves it; when the clock repeats it (fall back) the earlier of the two is used. Either
 * way a date has exactly one occurrence, so a change of clock never makes a workflow run twice in a day or not at all.
 */
final class ScheduleMath {

    /** How many occurrences {@link #latestDue} walks over before it gives up and takes the last one it saw. */
    private static final int MOST_STEPS = 20_000;

    private ScheduleMath() {
    }

    /**
     * The first occurrence strictly after the moment.
     *
     * @return the occurrence, or empty when there is none (a one-time moment that has passed)
     */
    static Optional<Instant> next(WorkflowDefinition.Trigger trigger, Instant after) {
        ZoneId zone = trigger.timeZone();
        return switch (trigger.kind()) {
            case EVERY_DAY, DAYS_AFTER_DATE -> Optional.of(daily(trigger.time(), zone, after));
            case EVERY_WEEK -> Optional.of(weekly(trigger, zone, after));
            case DATE_TIME -> {
                Instant moment = trigger.at().atZone(zone).toInstant();
                yield moment.isAfter(after) ? Optional.of(moment) : Optional.empty();
            }
            default -> throw new IllegalArgumentException("Not a scheduled trigger: " + trigger.kind());
        };
    }

    /**
     * The latest occurrence that is due at {@code now}, starting from an occurrence that was due. Occurrences that were
     * missed in between (the platform was down) are skipped: a workflow runs once for the latest of them, never in a
     * burst of catch-up runs.
     */
    static Instant latestDue(WorkflowDefinition.Trigger trigger, Instant due, Instant now) {
        Instant latest = due;
        for (int step = 0; step < MOST_STEPS; step++) {
            Optional<Instant> following = trigger.kind() == WorkflowDefinition.Kind.DATE_TIME ? Optional.empty()
                    : next(trigger, latest);
            if (following.isEmpty() || following.get().isAfter(now)) {
                break;
            }
            latest = following.get();
        }
        return latest;
    }

    /** The start of a local day in the zone (the first moment of the date, also where the clock skips midnight). */
    static Instant startOfDay(LocalDate date, ZoneId zone) {
        return date.atStartOfDay(zone).toInstant();
    }

    private static Instant daily(LocalTime time, ZoneId zone, Instant after) {
        LocalDate date = after.atZone(zone).toLocalDate();
        // The date of "after" in the zone may be a day earlier or later than the date its occurrence belongs to, so
        // start a day back and walk forward: the first occurrence after the moment is found within three dates.
        for (LocalDate candidate = date.minusDays(1); ; candidate = candidate.plusDays(1)) {
            Instant occurrence = ZonedDateTime.of(candidate, time, zone).toInstant();
            if (occurrence.isAfter(after)) {
                return occurrence;
            }
        }
    }

    private static Instant weekly(WorkflowDefinition.Trigger trigger, ZoneId zone, Instant after) {
        LocalDate date = after.atZone(zone).toLocalDate();
        for (LocalDate candidate = date.minusDays(1); ; candidate = candidate.plusDays(1)) {
            if (candidate.getDayOfWeek() != trigger.dayOfWeek()) {
                continue;
            }
            Instant occurrence = ZonedDateTime.of(candidate, trigger.time(), zone).toInstant();
            if (occurrence.isAfter(after)) {
                return occurrence;
            }
        }
    }
}
