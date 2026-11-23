package app.platform.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.identity.internal.RateLimitMath.Item;
import app.platform.testsupport.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;
import org.junit.jupiter.api.Test;

/**
 * The arithmetic of the limits on the public API (Sprint 32, ADR-0281, ADR-0282): the window, what is left, the reset,
 * the cost of a bulk call and the text of the headers, with the property that within one window on one counter the
 * requests accepted never add up to more than the limit.
 */
class RateLimitMathTest {

    private static final Instant NOON = Instant.parse("2026-11-23T12:00:00Z");

    @Test
    void whatIsLeftIsTheLimitMinusWhatWasUsedAndNeverBelowZero() {
        assertThat(RateLimitMath.remaining(600, 0)).isEqualTo(600);
        assertThat(RateLimitMath.remaining(600, 599)).isEqualTo(1);
        assertThat(RateLimitMath.remaining(600, 600)).isZero();
        assertThat(RateLimitMath.remaining(600, 9_000)).isZero();
        assertThat(RateLimitMath.remaining(600, -5)).isEqualTo(600);
    }

    @Test
    void aCountFitsExactlyUpToTheLimit() {
        assertThat(RateLimitMath.within(600, 600)).isTrue();
        assertThat(RateLimitMath.within(600, 601)).isFalse();
    }

    @Test
    void theQuotaWindowEndsAtTheNextMidnightInUtc() {
        assertThat(RateLimitMath.untilMidnight(Instant.parse("2026-11-23T23:59:30Z")))
                .isEqualTo(Duration.ofSeconds(30));
        assertThat(RateLimitMath.untilMidnight(NOON)).isEqualTo(Duration.ofHours(12));
        assertThat(RateLimitMath.untilMidnight(Instant.parse("2026-11-24T00:00:00Z"))).isEqualTo(Duration.ofHours(24));
        assertThat(RateLimitMath.untilMidnight(Instant.parse("2026-11-23T23:59:59.9999Z")))
                .isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    void aWaitIsRoundedUpToWholeSecondsAndIsNeverBelowOne() {
        assertThat(RateLimitMath.secondsUp(0)).isEqualTo(1);
        assertThat(RateLimitMath.secondsUp(1)).isEqualTo(1);
        assertThat(RateLimitMath.secondsUp(1000)).isEqualTo(1);
        assertThat(RateLimitMath.secondsUp(1001)).isEqualTo(2);
        assertThat(RateLimitMath.secondsUp(59_500)).isEqualTo(60);
    }

    @Test
    void aBulkCallCostsItsRecordsAndEveryOtherRequestCostsOne() {
        assertThat(RateLimitMath.cost(false, 500, 200)).isEqualTo(1);
        assertThat(RateLimitMath.cost(true, 150, 200)).isEqualTo(150);
        assertThat(RateLimitMath.cost(true, 0, 200)).isEqualTo(1);
        assertThat(RateLimitMath.cost(true, 5_000, 200)).isEqualTo(200);
        assertThat(RateLimitMath.cost(true, Integer.MAX_VALUE, 200)).isEqualTo(200);
    }

    @Test
    void theHeadersStateTheLimitsAndWhatIsLeftInTheFormOfTheDraft() {
        List<Item> items = List.of(new Item("minute", 600, 60, 120, 33), new Item("day", 500_000, 86_400, 499_000,
                40_000));

        assertThat(RateLimitMath.policyHeader(items)).isEqualTo("\"minute\";q=600;w=60, \"day\";q=500000;w=86400");
        assertThat(RateLimitMath.rateHeader(items)).isEqualTo("\"minute\";r=120;t=33, \"day\";r=499000;t=40000");
    }

    @Test
    void theTighterOfTwoLimitsOfOneWindowIsTheOneWithLessLeftAndTheLaterEndOnATie() {
        Item loose = new Item("minute", 3000, 60, 2000, 10);
        Item tight = new Item("minute", 600, 60, 5, 40);

        assertThat(RateLimitMath.tighter(loose, tight)).isSameAs(tight);
        assertThat(RateLimitMath.tighter(tight, loose)).isSameAs(tight);
        assertThat(RateLimitMath.tighter(new Item("minute", 10, 60, 5, 10), new Item("minute", 10, 60, 5, 30))
                .resetSeconds()).isEqualTo(30);
    }

    @Test
    void theKeysOfTheCountersChangeWithTheDayAndNeverCarryAName() {
        java.util.UUID tenant = java.util.UUID.randomUUID();

        assertThat(RateLimitMath.organizationDayKey(tenant, NOON)).isEqualTo("rl:d:org:" + tenant + ":20261123");
        assertThat(RateLimitMath.organizationDayKey(tenant, NOON.plus(Duration.ofHours(12))))
                .isEqualTo("rl:d:org:" + tenant + ":20261124");
    }

    @Property
    void withinOneWindowOnOneCounterTheRequestsAcceptedNeverAddUpToMoreThanTheLimit(
            @ForAll @IntRange(min = 1, max = 400) int limit,
            @ForAll List<@IntRange(min = 1, max = 60) Integer> costs) {
        LocalCounters counters = new LocalCounters(new MutableClock(NOON));
        long accepted = 0;
        for (int cost : costs) {
            long count = counters.add("k", RateLimitMath.MINUTE, cost);
            if (RateLimitMath.within(limit, count)) {
                accepted += cost;
            }
        }

        assertThat(accepted).isLessThanOrEqualTo(limit);
    }

    @Property
    void theHeaderNumbersAlwaysAgreeWithTheCounter(@ForAll @IntRange(min = 1, max = 1000) int limit,
            @ForAll @LongRange(min = 0, max = 5000) long used) {
        long remaining = RateLimitMath.remaining(limit, used);

        assertThat(remaining).isBetween(0L, (long) limit);
        assertThat(remaining + Math.min(used, limit)).isEqualTo(limit);
    }

    @Test
    void aWindowThatEndedStartsAgainFromZero() {
        MutableClock clock = new MutableClock(NOON);
        LocalCounters counters = new LocalCounters(clock);

        assertThat(counters.add("k", RateLimitMath.MINUTE, 7)).isEqualTo(7);
        assertThat(counters.remainingMillis("k")).isEqualTo(60_000);
        clock.advance(Duration.ofSeconds(45));
        assertThat(counters.remainingMillis("k")).isEqualTo(15_000);
        assertThat(counters.add("k", RateLimitMath.MINUTE, 1)).isEqualTo(8);
        clock.advance(Duration.ofSeconds(16));
        assertThat(counters.current("k")).isZero();
        assertThat(counters.add("k", RateLimitMath.MINUTE, 1)).isEqualTo(1);
    }
}
