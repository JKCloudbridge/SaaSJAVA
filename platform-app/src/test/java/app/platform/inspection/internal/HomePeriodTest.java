package app.platform.inspection.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.platform.inspection.internal.HomePeriod.Key;
import app.platform.inspection.internal.HomePeriod.Window;
import app.platformapi.ApiException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The arithmetic of the home page (FA9, ADR-0203): periods, the period before, days, months, averages and shares. */
class HomePeriodTest {

    private static final Instant NOW = Instant.parse("2026-10-26T15:30:00Z");

    @Test
    void anEmptyRequestIsThirtyDaysAndAnythingElseThatIsNotAPeriodIsRefused() {
        assertThat(HomePeriod.parse(null)).isEqualTo(Key.DAYS_30);
        assertThat(HomePeriod.parse(" ")).isEqualTo(Key.DAYS_30);
        assertThat(HomePeriod.parse("7D")).isEqualTo(Key.DAYS_7);
        assertThat(HomePeriod.parse("12m")).isEqualTo(Key.MONTHS_12);
        assertThatThrownBy(() -> HomePeriod.parse("yesterday")).isInstanceOf(ApiException.class);
    }

    @Test
    void aPeriodEndsAtTheStartOfTomorrowSoTodayIsInIt() {
        Window window = HomePeriod.of(Key.DAYS_7, NOW);

        assertThat(window.to()).isEqualTo(Instant.parse("2026-10-27T00:00:00Z"));
        assertThat(window.from()).isEqualTo(Instant.parse("2026-10-20T00:00:00Z"));
        assertThat(window.contains(NOW)).isTrue();
        assertThat(window.contains(Instant.parse("2026-10-20T00:00:00Z"))).as("the first moment is in").isTrue();
        assertThat(window.contains(Instant.parse("2026-10-27T00:00:00Z"))).as("the last is not").isFalse();
        assertThat(window.contains(null)).isFalse();
    }

    @Test
    void thePeriodBeforeIsAsLongAndMeetsItWithoutAGapOrAnOverlap() {
        for (Key key : Key.values()) {
            Window window = HomePeriod.of(key, NOW);

            assertThat(window.previousTo()).isEqualTo(window.from());
            assertThat(Duration.between(window.previousFrom(), window.previousTo()))
                    .isEqualTo(Duration.between(window.from(), window.to()));
            assertThat(window.containsPrevious(window.from())).isFalse();
            assertThat(window.containsPrevious(window.from().minusSeconds(1))).isTrue();
            assertThat(window.contains(window.from().minusSeconds(1))).isFalse();
        }
    }

    @Test
    void everyDayOfThePeriodIsListedEvenWhenNothingHappenedOnIt() {
        List<String> days = HomePeriod.days(HomePeriod.of(Key.DAYS_7, NOW));

        assertThat(days).containsExactly("2026-10-20", "2026-10-21", "2026-10-22", "2026-10-23", "2026-10-24",
                "2026-10-25", "2026-10-26");
        assertThat(HomePeriod.days(HomePeriod.of(Key.DAYS_30, NOW))).hasSize(30);
        assertThat(HomePeriod.days(HomePeriod.of(Key.MONTHS_12, NOW))).hasSize(365);
    }

    @Test
    void aMomentFallsOnTheDayOfUtcAndInTheMonthOfUtc() {
        assertThat(HomePeriod.dayOf(Instant.parse("2026-10-26T23:59:59Z"))).isEqualTo("2026-10-26");
        assertThat(HomePeriod.dayOf(Instant.parse("2026-10-27T00:00:00Z"))).isEqualTo("2026-10-27");
        assertThat(HomePeriod.monthOf(Instant.parse("2026-10-31T23:59:59Z"))).isEqualTo("2026-10");
    }

    @Test
    void theTwelveMonthsEndWithTheCurrentOneAndCrossTheNewYear() {
        List<String> months = HomePeriod.months(Instant.parse("2026-02-10T00:00:00Z"), 12);

        assertThat(months).hasSize(12);
        assertThat(months.get(0)).isEqualTo("2025-03");
        assertThat(months.get(11)).isEqualTo("2026-02");
        assertThat(HomePeriod.monthsStart(Instant.parse("2026-02-10T00:00:00Z"), 12))
                .isEqualTo(Instant.parse("2025-03-01T00:00:00Z"));
    }

    @Test
    void anAverageOfDaysIsRoundedToOneDecimalAndIsNullWhenThereIsNothingToAverage() {
        assertThat(HomePeriod.averageDays(List.of(Duration.ofDays(2), Duration.ofDays(3)))).isEqualTo(2.5);
        assertThat(HomePeriod.averageDays(List.of(Duration.ofHours(36)))).isEqualTo(1.5);
        assertThat(HomePeriod.averageDays(List.of(Duration.ofHours(1)))).isEqualTo(0.0);
        assertThat(HomePeriod.averageDays(List.of())).isNull();
    }

    @Test
    void aShareIsAPercentageWithOneDecimalAndIsNullWithoutAWhole() {
        assertThat(HomePeriod.percent(1, 3)).isEqualTo(33.3);
        assertThat(HomePeriod.percent(1, 2)).isEqualTo(50.0);
        assertThat(HomePeriod.percent(0, 5)).isEqualTo(0.0);
        assertThat(HomePeriod.percent(0, 0)).isNull();
    }

    @Test
    void wholeDaysRoundDownAndNeverGoBelowZero() {
        assertThat(HomePeriod.wholeDays(NOW.minus(Duration.ofHours(60)), NOW)).isEqualTo(2);
        assertThat(HomePeriod.wholeDays(NOW.plusSeconds(5), NOW)).isZero();
    }
}
