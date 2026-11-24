package app.platform.identity.internal;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The arithmetic of the limits on the public API as pure functions (Sprint 32, ADR-0281, ADR-0282): the windows, what
 * is left of them, the keys of the counters and the text of the rate-limit headers. No clock, no counter, no
 * database, so every number of a header and every bound is tested as a property.
 *
 * <p>Two kinds of window. The rate is a fixed window of a minute that starts at the first request that finds none, so
 * the time it has left is exactly the time to reset. The quota is the calendar day in UTC: its window ends at the next
 * midnight, so a client can plan by it. Both are counted in the same shared counters and both state their numbers in
 * the
 * headers of the IETF draft: {@code RateLimit-Policy} says the quota and the window of each limit, {@code RateLimit}
 * says what is left and when the window ends.
 */
final class RateLimitMath {

    /** The window of the rate. */
    static final Duration MINUTE = Duration.ofMinutes(1);

    /** The name of the rate in the headers. */
    static final String MINUTE_NAME = "minute";

    /** The name of the quota in the headers. */
    static final String DAY_NAME = "day";

    private static final DateTimeFormatter DAY_KEY = DateTimeFormatter.BASIC_ISO_DATE;

    private RateLimitMath() {
    }

    /**
     * One limit as a header states it.
     *
     * @param name {@code minute} or {@code day}
     * @param quota what the window allows
     * @param windowSeconds the length of a whole window
     * @param remaining what is left, never below zero
     * @param resetSeconds the seconds until the window ends and the count starts again
     */
    record Item(String name, long quota, long windowSeconds, long remaining, long resetSeconds) {
    }

    /** What is left of a window of {@code quota} after {@code used}, never below zero. */
    static long remaining(long quota, long used) {
        return Math.max(0, quota - Math.max(0, used));
    }

    /** Whether a count that was just made is still inside the quota: the requests that fit are exactly those. */
    static boolean within(long quota, long countAfter) {
        return countAfter <= quota;
    }

    /** The time from {@code now} to the next midnight in UTC: the window of the quota; at least a second. */
    static Duration untilMidnight(Instant now) {
        Instant next = LocalDate.ofInstant(now, ZoneOffset.UTC).plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Duration left = Duration.between(now, next);
        return left.compareTo(Duration.ofSeconds(1)) < 0 ? Duration.ofSeconds(1) : left;
    }

    /** The whole seconds to wait for a window that has {@code millis} left: rounded up, never below one. */
    static long secondsUp(long millis) {
        return Math.max(1, (millis + 999) / 1000);
    }

    /** The counter of the rate of one caller (a connected application or a member) of an organization. */
    static String callerMinuteKey(UUID tenant, boolean application, UUID caller) {
        return "rl:m:" + (application ? "app" : "mem") + ":" + tenant + ":" + caller;
    }

    /** The counter of the rate of an organization as a whole. */
    static String organizationMinuteKey(UUID tenant) {
        return "rl:m:org:" + tenant;
    }

    /** The counter of the quota of an organization for the UTC day of {@code now}. */
    static String organizationDayKey(UUID tenant, Instant now) {
        return "rl:d:org:" + tenant + ":" + DAY_KEY.format(LocalDate.ofInstant(now, ZoneOffset.UTC));
    }

    /** The cost of a request: a bulk call costs its records (at least one), and no request costs more than the cap. */
    static long cost(boolean bulk, int records, int cap) {
        return bulk ? Math.min(cap, Math.max(1, records)) : 1;
    }

    /**
     * Keeps the more constraining of two limits of the same window, which is the one with less left (the one that ends
     * later when they are equal). The header says that limit's numbers and never which key it belongs to.
     */
    static Item tighter(Item first, Item second) {
        if (first.remaining() != second.remaining()) {
            return first.remaining() < second.remaining() ? first : second;
        }
        return first.resetSeconds() >= second.resetSeconds() ? first : second;
    }

    /** The value of the {@code RateLimit-Policy} header: one item for each limit, by window name. */
    static String policyHeader(List<Item> items) {
        List<String> parts = new ArrayList<>();
        for (Item item : items) {
            parts.add(String.format(Locale.ROOT, "\"%s\";q=%d;w=%d", item.name(), item.quota(), item.windowSeconds()));
        }
        return String.join(", ", parts);
    }

    /** The value of the {@code RateLimit} header: what is left and when the window ends, for each limit. */
    static String rateHeader(List<Item> items) {
        List<String> parts = new ArrayList<>();
        for (Item item : items) {
            parts.add(String.format(Locale.ROOT, "\"%s\";r=%d;t=%d", item.name(), item.remaining(),
                    item.resetSeconds()));
        }
        return String.join(", ", parts);
    }
}
