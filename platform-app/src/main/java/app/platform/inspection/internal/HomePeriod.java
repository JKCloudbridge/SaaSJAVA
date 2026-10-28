package app.platform.inspection.internal;

import app.platformapi.ApiException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * The arithmetic of the Quality Management System home page (FA9, ADR-0203): which days a period covers, the period
 * before it that a tile is compared with, the days and months of a chart, and the averages and shares of a tile. Pure:
 * it reads no record and no clock (the moment is handed in), so every rule is tested without a database.
 *
 * <p>Days are the organization's days in UTC: the platform keeps no time zone for an organization yet, so a day starts
 * and ends at midnight UTC and the page says so.
 */
final class HomePeriod {

    /** The periods a person may choose. */
    enum Key {
        DAYS_7("7d", 7), DAYS_30("30d", 30), DAYS_90("90d", 90), MONTHS_12("12m", 365);

        private final String text;
        private final int days;

        Key(String text, int days) {
            this.text = text;
            this.days = days;
        }

        String text() {
            return text;
        }

        int days() {
            return days;
        }
    }

    /**
     * A period and the one before it, both half open: the first moment is in, the last is not.
     *
     * @param key the period chosen
     * @param from the start of the first day
     * @param to the start of the day after the last (tomorrow at midnight UTC, so today is in)
     * @param previousFrom the start of the period before
     * @param previousTo the end of the period before, which is {@code from}
     */
    record Window(Key key, Instant from, Instant to, Instant previousFrom, Instant previousTo) {

        /** Whether the moment falls in the period. */
        boolean contains(Instant moment) {
            return moment != null && !moment.isBefore(from) && moment.isBefore(to);
        }

        /** Whether the moment falls in the period before. */
        boolean containsPrevious(Instant moment) {
            return moment != null && !moment.isBefore(previousFrom) && moment.isBefore(previousTo);
        }
    }

    private HomePeriod() {
    }

    /**
     * The period a request asked for; an empty request is the last 30 days.
     *
     * @throws ApiException {@code VALIDATION_ERROR} for a text that is not one of the periods
     */
    static Key parse(String text) {
        if (text == null || text.isBlank()) {
            return Key.DAYS_30;
        }
        String wanted = text.trim().toLowerCase(Locale.ROOT);
        for (Key key : Key.values()) {
            if (key.text.equals(wanted)) {
                return key;
            }
        }
        throw ApiException.validation("period", "Choose 7d, 30d, 90d or 12m.");
    }

    /** The period that ends with the day of {@code now} (today is in it) and the one before it. */
    static Window of(Key key, Instant now) {
        Instant to = now.atZone(ZoneOffset.UTC).toLocalDate().plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant from = to.minus(Duration.ofDays(key.days));
        return new Window(key, from, to, from.minus(Duration.ofDays(key.days)), from);
    }

    /** Every day of the period, oldest first, as {@code yyyy-MM-dd}: a day with nothing in it is still a day. */
    static List<String> days(Window window) {
        List<String> days = new ArrayList<>();
        LocalDate day = window.from().atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate end = window.to().atZone(ZoneOffset.UTC).toLocalDate();
        while (day.isBefore(end)) {
            days.add(day.toString());
            day = day.plusDays(1);
        }
        return days;
    }

    /** The day a moment falls on, as {@code yyyy-MM-dd}. */
    static String dayOf(Instant moment) {
        return moment.atZone(ZoneOffset.UTC).toLocalDate().toString();
    }

    /** The {@code count} months that end with the month of {@code now}, oldest first, as {@code yyyy-MM}. */
    static List<String> months(Instant now, int count) {
        YearMonth last = YearMonth.from(now.atZone(ZoneOffset.UTC));
        List<String> months = new ArrayList<>();
        for (int back = count - 1; back >= 0; back--) {
            months.add(last.minusMonths(back).toString());
        }
        return months;
    }

    /** The month a moment falls in, as {@code yyyy-MM}. */
    static String monthOf(Instant moment) {
        return YearMonth.from(moment.atZone(ZoneOffset.UTC)).toString();
    }

    /** The first moment of the oldest of {@code count} months that end with the month of {@code now}. */
    static Instant monthsStart(Instant now, int count) {
        return YearMonth.from(now.atZone(ZoneOffset.UTC)).minusMonths(count - 1L).atDay(1).atStartOfDay()
                .toInstant(ZoneOffset.UTC);
    }

    /** The average of some durations in days, one decimal; null when there are none (a tile says so, never zero). */
    static Double averageDays(Collection<Duration> durations) {
        if (durations.isEmpty()) {
            return null;
        }
        double seconds = durations.stream().mapToLong(Duration::getSeconds).average().orElse(0);
        return round(seconds / 86_400.0);
    }

    /** The share of {@code part} in {@code whole} as a percentage with one decimal; null when there is no whole. */
    static Double percent(long part, long whole) {
        return whole <= 0 ? null : round(part * 100.0 / whole);
    }

    /** Whole days between two moments, rounded down, never below zero. */
    static long wholeDays(Instant from, Instant to) {
        return Math.max(0, Duration.between(from, to).toDays());
    }

    private static double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
