package app.platform.identity.internal;

import app.platform.identity.internal.RateLimitMath.Item;
import app.platform.sharedkernel.limits.RateLimitSettings.Limits;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The one place that decides whether a request of the public API is allowed, and what the headers say (Sprint 32,
 * ADR-0280 to ADR-0282). It counts in the shared counters, so every instance agrees: Redis while it answers, the memory
 * of each instance while it does not (the decision of Sprint 3). A Redis outage therefore makes the count less
 * precise and never refuses a request.
 *
 * <p><strong>Three counters, in a fixed order.</strong> The caller's own rate (a connected application, or the member
 * for a person's own token) is charged first. Only a request that fits it is charged to the organization's rate and to
 * the organization's quota for the day; so an application that is over its limit and keeps asking cannot use up what
 * the others share. A request that is refused for the organization's number has already been charged to its caller,
 * which is right: that caller did ask.
 *
 * <p>The cost of a request is 1; a bulk call costs its records (the caller works that out and hands it in). A repeated
 * keyed request counts like any other, because it reached the server. Nothing here knows an object, a record, a value
 * or a name: keys are made of identifiers and the answer is numbers.
 */
@Component
class RateLimits {

    /**
     * The caller whose rate is counted.
     *
     * @param tenant the organization the host names
     * @param application whether the caller is a connected application (else a member's own token)
     * @param id the application's registration identifier, or the member's user identifier
     */
    record Caller(UUID tenant, boolean application, UUID id) {
    }

    /**
     * The decision.
     *
     * @param allowed whether the request may go on
     * @param items what the headers say: the tighter rate and the quota
     * @param retryAfterSeconds when refused, the whole seconds to wait; otherwise 0
     */
    record Decision(boolean allowed, List<Item> items, long retryAfterSeconds) {

        Decision {
            items = List.copyOf(items);
        }
    }

    private final Counters counters;
    private final LimitOverrides overrides;
    private final Clock clock;
    private final MeterRegistry meters;

    RateLimits(Counters counters, LimitOverrides overrides, Clock clock, MeterRegistry meters) {
        this.counters = counters;
        this.overrides = overrides;
        this.clock = clock;
        this.meters = meters;
    }

    /** Charges {@code cost} to the caller and the organization and says whether the request is allowed. */
    Decision check(Caller caller, long cost) {
        Instant now = clock.instant();
        Limits limits = overrides.limitsFor(caller.tenant());
        long perCaller = limits.applicationPerMinute();
        String callerKey = RateLimitMath.callerMinuteKey(caller.tenant(), caller.application(), caller.id());

        long callerCount = counters.add(callerKey, RateLimitMath.MINUTE, cost);
        Item callerItem = minute(perCaller, callerCount, callerKey);
        if (!RateLimitMath.within(perCaller, callerCount)) {
            // Not charged to the organization: whoever is refused for their own number takes nothing from the others.
            return refused(List.of(callerItem, untouchedDay(caller.tenant(), limits, now)), callerItem.resetSeconds(),
                    false);
        }

        String orgMinuteKey = RateLimitMath.organizationMinuteKey(caller.tenant());
        long orgMinuteCount = counters.add(orgMinuteKey, RateLimitMath.MINUTE, cost);
        Item orgMinute = minute(limits.organizationPerMinute(), orgMinuteCount, orgMinuteKey);

        Duration dayWindow = RateLimitMath.untilMidnight(now);
        String dayKey = RateLimitMath.organizationDayKey(caller.tenant(), now);
        long dayCount = counters.add(dayKey, dayWindow, cost);
        Item day = new Item(RateLimitMath.DAY_NAME, limits.organizationPerDay(), Duration.ofDays(1).toSeconds(),
                RateLimitMath.remaining(limits.organizationPerDay(), dayCount),
                RateLimitMath.secondsUp(left(dayKey, dayWindow)));

        Item rate = RateLimitMath.tighter(callerItem, orgMinute);
        List<Item> items = List.of(rate, day);
        boolean minuteOk = RateLimitMath.within(limits.organizationPerMinute(), orgMinuteCount);
        boolean dayOk = RateLimitMath.within(limits.organizationPerDay(), dayCount);
        if (minuteOk && dayOk) {
            meters.counter("platform.limits.requests", "result", "allowed").increment();
            return new Decision(true, items, 0);
        }
        long wait = Math.max(minuteOk ? 0 : orgMinute.resetSeconds(), dayOk ? 0 : day.resetSeconds());
        return refused(items, wait, !dayOk);
    }

    private Decision refused(List<Item> items, long retryAfterSeconds, boolean quota) {
        meters.counter("platform.limits.requests", "result", "limited").increment();
        if (quota) {
            meters.counter("platform.limits.quota.exhausted").increment();
        }
        return new Decision(false, items, Math.max(1, retryAfterSeconds));
    }

    private Item minute(long quota, long count, String key) {
        return new Item(RateLimitMath.MINUTE_NAME, quota, RateLimitMath.MINUTE.toSeconds(),
                RateLimitMath.remaining(quota, count), RateLimitMath.secondsUp(left(key, RateLimitMath.MINUTE)));
    }

    /** The day as it stands, read without adding anything: a request refused for its caller does not touch it. */
    private Item untouchedDay(UUID tenant, Limits limits, Instant now) {
        Duration window = RateLimitMath.untilMidnight(now);
        String key = RateLimitMath.organizationDayKey(tenant, now);
        return new Item(RateLimitMath.DAY_NAME, limits.organizationPerDay(), Duration.ofDays(1).toSeconds(),
                RateLimitMath.remaining(limits.organizationPerDay(), counters.current(key)),
                RateLimitMath.secondsUp(left(key, window)));
    }

    /** What the window of the counter has left; the whole window when the counter cannot say. */
    private long left(String key, Duration whole) {
        long left = counters.remainingMillis(key);
        return left > 0 ? Math.min(left, whole.toMillis()) : whole.toMillis();
    }

    /** What the organization's counters hold now, for the usage page: a read that adds nothing. */
    Item organizationMinute(UUID tenant, Limits limits) {
        String key = RateLimitMath.organizationMinuteKey(tenant);
        return new Item(RateLimitMath.MINUTE_NAME, limits.organizationPerMinute(), RateLimitMath.MINUTE.toSeconds(),
                RateLimitMath.remaining(limits.organizationPerMinute(), counters.current(key)),
                counters.current(key) == 0 ? 0 : RateLimitMath.secondsUp(left(key, RateLimitMath.MINUTE)));
    }

    /** The organization's quota for today, read without adding anything. */
    Item organizationDay(UUID tenant, Limits limits) {
        Instant now = clock.instant();
        String key = RateLimitMath.organizationDayKey(tenant, now);
        long used = counters.current(key);
        return new Item(RateLimitMath.DAY_NAME, limits.organizationPerDay(), Duration.ofDays(1).toSeconds(),
                RateLimitMath.remaining(limits.organizationPerDay(), used),
                RateLimitMath.secondsUp(left(key, RateLimitMath.untilMidnight(now))));
    }

    /** The rate of one caller, read without adding anything. */
    Item callerMinute(Caller caller, Limits limits) {
        String key = RateLimitMath.callerMinuteKey(caller.tenant(), caller.application(), caller.id());
        long used = counters.current(key);
        return new Item(RateLimitMath.MINUTE_NAME, limits.applicationPerMinute(), RateLimitMath.MINUTE.toSeconds(),
                RateLimitMath.remaining(limits.applicationPerMinute(), used),
                used == 0 ? 0 : RateLimitMath.secondsUp(left(key, RateLimitMath.MINUTE)));
    }
}
