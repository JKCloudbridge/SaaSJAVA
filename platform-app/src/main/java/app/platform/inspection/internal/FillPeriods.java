package app.platform.inspection.internal;

import java.time.Clock;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * The month of a monthly fill-in (MC2A, ADR-0260): what a month is written as, which month is "this month" and which
 * months may be started. A month is written {@code YYYY-MM}. "This month" is the month of the clock in UTC, so the
 * answer is the same for every person of an organization wherever they sit; a person in a time zone ahead of UTC can
 * therefore start the new month a few hours after they see it begin. An earlier month may be started to catch up; a
 * month that has not begun may not. Pure apart from the clock.
 */
@Component
class FillPeriods {

    private static final Pattern SHAPE = Pattern.compile("[0-9]{4}-(0[1-9]|1[0-2])");

    private final Clock clock;

    FillPeriods(Clock clock) {
        this.clock = clock;
    }

    /** This month, as {@code YYYY-MM}. */
    String current() {
        return YearMonth.now(clock.withZone(ZoneOffset.UTC)).toString();
    }

    /** The month written, or empty when it is not a month written as {@code YYYY-MM}. */
    static Optional<YearMonth> parse(String text) {
        if (text == null || !SHAPE.matcher(text).matches()) {
            return Optional.empty();
        }
        try {
            return Optional.of(YearMonth.parse(text));
        } catch (DateTimeParseException notAMonth) {
            return Optional.empty();
        }
    }

    /** Whether the month has not begun yet. */
    boolean inTheFuture(YearMonth month) {
        return month.isAfter(YearMonth.now(clock.withZone(ZoneOffset.UTC)));
    }
}
