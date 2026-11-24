package app.platform.identity.internal;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the limits on the public API (Sprint 32, ADR-0280 to ADR-0283). Every number has a safe, generous
 * default, so that the {@code local} profile, the tests of other sprints and the benchmark scripts are never held back;
 * the {@code test} profile sets them far out of the way.
 *
 * @param enabled whether the limits are in force ({@code PLATFORM_RATE_LIMITS_ENABLED}, the kill switch): false makes
 *        the rate-limit headers disappear and no request be refused for a limit; the setting is read at start, so it
 *        needs a restart of the application and nothing else
 * @param organizationPerMinute requests a minute for one organization as a whole, whoever asks (default 3,000)
 * @param organizationPerDay requests a day for one organization as a whole, counted from midnight UTC (default
 *        500,000)
 * @param applicationPerMinute requests a minute for each connected application and for each member's own token
 *        (default 600)
 * @param ceilings the highest numbers an override of the platform console may set
 * @param overrideCache how long an instance keeps the override of an organization it has read before it reads it
 *        again; a change made on this instance is seen at once, on another within this time (default 10 seconds)
 * @param bulkMaxCost the most one request may cost: a bulk call costs its records, never more than this (default 200,
 *        the size of a bulk call)
 */
@ConfigurationProperties("platform.limits.public-api")
record RateLimitProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("3000") int organizationPerMinute,
        @DefaultValue("500000") long organizationPerDay,
        @DefaultValue("600") int applicationPerMinute,
        @DefaultValue Ceilings ceilings,
        @DefaultValue("10s") Duration overrideCache,
        @DefaultValue("200") int bulkMaxCost) {

    /**
     * The highest numbers an override may set.
     *
     * @param perMinute the highest number of requests a minute (default 10,000,000)
     * @param perDay the highest number of requests a day (default 1,000,000,000)
     */
    record Ceilings(@DefaultValue("10000000") int perMinute, @DefaultValue("1000000000") long perDay) {

        Ceilings {
            if (perMinute < 1 || perDay < 1) {
                throw new IllegalArgumentException("platform.limits.public-api.ceilings.* is out of range");
            }
        }
    }

    RateLimitProperties {
        if (organizationPerMinute < 1 || organizationPerMinute > ceilings.perMinute() || applicationPerMinute < 1
                || applicationPerMinute > ceilings.perMinute() || organizationPerDay < 1
                || organizationPerDay > ceilings.perDay() || overrideCache.isNegative()
                || overrideCache.compareTo(Duration.ofMinutes(10)) > 0 || bulkMaxCost < 1 || bulkMaxCost > 10_000) {
            throw new IllegalArgumentException("platform.limits.public-api.* is out of range");
        }
    }
}
