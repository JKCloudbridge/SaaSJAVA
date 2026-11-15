package app.platform.inspection.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

/**
 * The month of a monthly fill-in (MC2A, ADR-0260), with no database: how a month is written, which month is this month
 * and which may be started.
 */
class FillPeriodsTest {

    private static FillPeriods at(String instant) {
        return new FillPeriods(Clock.fixed(Instant.parse(instant), ZoneId.of("UTC")));
    }

    @Test
    void aMonthIsWrittenAsYearAndMonthAndNothingElseIsOne() {
        assertThat(FillPeriods.parse("2026-05")).contains(YearMonth.of(2026, 5));
        assertThat(FillPeriods.parse("2026-12")).contains(YearMonth.of(2026, 12));
        for (String bad : new String[] {"2026-13", "2026-00", "2026-5", "26-05", "2026/05", "2026-05-01", " 2026-05",
            "", "May 2026", "2026-05\n"}) {
            assertThat(FillPeriods.parse(bad)).as("[" + bad + "]").isEmpty();
        }
        assertThat(FillPeriods.parse(null)).isEmpty();
    }

    @Test
    void thisMonthIsTheMonthOfTheClockInUtcWhateverZoneThePersonIsIn() {
        assertThat(at("2026-05-31T23:59:59Z").current()).isEqualTo("2026-05");
        assertThat(at("2026-06-01T00:00:00Z").current()).isEqualTo("2026-06");
        assertThat(at("2026-01-01T00:00:00Z").current()).isEqualTo("2026-01");
    }

    @Test
    void anEarlierMonthMayBeStartedAndAMonthThatHasNotBegunMayNot() {
        FillPeriods periods = at("2026-05-15T12:00:00Z");

        assertThat(periods.inTheFuture(YearMonth.of(2026, 5))).as("this month").isFalse();
        assertThat(periods.inTheFuture(YearMonth.of(2026, 4))).as("catching up").isFalse();
        assertThat(periods.inTheFuture(YearMonth.of(2019, 1))).isFalse();
        assertThat(periods.inTheFuture(YearMonth.of(2026, 6))).as("next month").isTrue();
        assertThat(periods.inTheFuture(YearMonth.of(2027, 1))).isTrue();
    }
}
