package app.platform.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.identity.internal.RateLimitMath.Item;
import app.platform.identity.internal.RateLimits.Caller;
import app.platform.identity.internal.RateLimits.Decision;
import app.platform.sharedkernel.limits.RateLimitSettings.Limits;
import app.platform.testsupport.MutableClock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The decision of the limits on the public API on counters that need no Redis (Sprint 32, ADR-0280, ADR-0281): who is
 * charged for what, in which order, what the headers say and when the windows end. The same code runs against Redis;
 * the
 * three-instance and the outage tests prove the sharing and the fallback.
 */
class RateLimitsTest {

    private static final Instant NOON = Instant.parse("2026-11-23T12:00:00Z");
    private static final UUID TENANT = UUID.randomUUID();

    private final MutableClock clock = new MutableClock(NOON);
    private final LocalCounters counters = new LocalCounters(clock);

    private RateLimits limits(int organizationPerMinute, long organizationPerDay, int applicationPerMinute) {
        RateLimitProperties properties = new RateLimitProperties(true, organizationPerMinute, organizationPerDay,
                applicationPerMinute, new RateLimitProperties.Ceilings(10_000_000, 1_000_000_000L),
                Duration.ofSeconds(10), 200);
        LimitOverrides overrides = new LimitOverrides(null, properties, null, clock) {
            @Override
            Limits limitsFor(UUID tenant) {
                return new Limits(organizationPerMinute, organizationPerDay, applicationPerMinute, false);
            }
        };
        return new RateLimits(counters, overrides, clock, new SimpleMeterRegistry());
    }

    private static Caller application() {
        return new Caller(TENANT, true, UUID.randomUUID());
    }

    @Test
    void theHeadersOfTheFirstRequestAreExact() {
        RateLimits limits = limits(100, 1000, 5);

        Decision first = limits.check(application(), 1);

        assertThat(first.allowed()).isTrue();
        assertThat(RateLimitMath.policyHeader(first.items())).isEqualTo("\"minute\";q=5;w=60, \"day\";q=1000;w=86400");
        assertThat(RateLimitMath.rateHeader(first.items())).isEqualTo("\"minute\";r=4;t=60, \"day\";r=999;t=43200");
    }

    @Test
    void theNumbersFallWithEveryRequestAndTheWindowCountsDown() {
        RateLimits limits = limits(100, 1000, 5);
        Caller caller = application();

        limits.check(caller, 1);
        clock.advance(Duration.ofSeconds(20));
        Decision second = limits.check(caller, 1);

        Item minute = second.items().get(0);
        assertThat(minute.remaining()).isEqualTo(3);
        assertThat(minute.resetSeconds()).isEqualTo(40);
        assertThat(second.items().get(1).remaining()).isEqualTo(998);
    }

    @Test
    void aCallerOverItsOwnLimitIsRefusedWithTheTimeToWaitAndTakesNothingFromTheOrganization() {
        RateLimits limits = limits(100, 1000, 5);
        Caller greedy = application();
        for (int i = 0; i < 5; i++) {
            assertThat(limits.check(greedy, 1).allowed()).isTrue();
        }

        Decision refused = limits.check(greedy, 1);
        for (int i = 0; i < 20; i++) {
            limits.check(greedy, 1);
        }

        assertThat(refused.allowed()).isFalse();
        assertThat(refused.retryAfterSeconds()).isBetween(1L, 60L);
        assertThat(counters.current(RateLimitMath.organizationMinuteKey(TENANT)))
                .as("a refusal for its own number is not charged to the others").isEqualTo(5);
        assertThat(limits.check(application(), 1).allowed()).as("another application is untouched").isTrue();
    }

    @Test
    void aPersonsOwnTokenAndAnApplicationHaveSeparateCounters() {
        RateLimits limits = limits(100, 1000, 2);
        UUID same = UUID.randomUUID();
        Caller app = new Caller(TENANT, true, same);
        Caller person = new Caller(TENANT, false, same);
        limits.check(app, 1);
        limits.check(app, 1);

        assertThat(limits.check(app, 1).allowed()).isFalse();
        assertThat(limits.check(person, 1).allowed()).isTrue();
    }

    @Test
    void theOrganizationTotalHoldsAcrossApplications() {
        RateLimits limits = limits(10, 1000, 100);
        Caller first = application();
        Caller second = application();
        int accepted = 0;
        for (int i = 0; i < 8; i++) {
            if (limits.check(first, 1).allowed()) {
                accepted++;
            }
            if (limits.check(second, 1).allowed()) {
                accepted++;
            }
        }

        assertThat(accepted).isEqualTo(10);
    }

    @Test
    void aBulkCallCostsItsRecords() {
        RateLimits limits = limits(1000, 100_000, 200);

        Decision bulk = limits.check(application(), 150);

        assertThat(bulk.allowed()).isTrue();
        assertThat(bulk.items().get(0).remaining()).isEqualTo(50);
        assertThat(bulk.items().get(1).remaining()).isEqualTo(99_850);
    }

    @Test
    void aBulkCallThatDoesNotFitIsRefusedWhole() {
        RateLimits limits = limits(1000, 100_000, 100);

        assertThat(limits.check(application(), 150).allowed()).isFalse();
    }

    @Test
    void theQuotaEndsAtMidnightUtcAndStartsAgain() {
        clock.advance(Duration.ofHours(11).plusMinutes(59).plusSeconds(58));
        RateLimits limits = limits(100, 3, 100);
        Caller caller = application();
        for (int i = 0; i < 3; i++) {
            assertThat(limits.check(caller, 1).allowed()).isTrue();
        }

        Decision refused = limits.check(caller, 1);
        clock.advance(Duration.ofSeconds(3));
        Decision tomorrow = limits.check(caller, 1);

        assertThat(refused.allowed()).isFalse();
        assertThat(refused.retryAfterSeconds()).isEqualTo(2);
        assertThat(tomorrow.allowed()).isTrue();
        assertThat(tomorrow.items().get(1).remaining()).isEqualTo(2);
    }

    @Test
    void aRefusalNeverSaysWhichLimitWasFull() {
        Decision byApplication = refusedBy(limits(100, 1000, 1));
        Decision byOrganization = refusedBy(limits(1, 1000, 100));
        Decision byQuota = refusedBy(limits(100, 1, 100));

        for (Decision refused : List.of(byApplication, byOrganization, byQuota)) {
            assertThat(refused.allowed()).isFalse();
            assertThat(refused.items()).extracting(Item::name).containsExactly("minute", "day");
            assertThat(refused.retryAfterSeconds()).isPositive();
        }
    }

    private Decision refusedBy(RateLimits limits) {
        Caller caller = application();
        limits.check(caller, 1);
        return limits.check(caller, 1);
    }
}
