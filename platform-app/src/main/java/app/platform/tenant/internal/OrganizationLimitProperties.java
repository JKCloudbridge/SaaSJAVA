package app.platform.tenant.internal;

import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The limits of one organization (Sprint 16, ADR-0108).
 *
 * @param enabled whether the limits are applied (on in every profile; a test may switch them off)
 * @param maxConcurrent the most API requests of one organization that run at once on one instance (default 6). It is
 *        deliberately below the size of the database connection pool (10 by default): an organization that reaches its
 *        limit still leaves connections for the others
 * @param maxWaiting the most requests of one organization that may wait for a turn at once (default 6); the next one is
 *        refused at once, so a flood cannot hold request threads
 * @param maxWait how long a request waits for a turn before it is refused (default 2 seconds)
 * @param queryTimeout the time limit of each statement of a transaction that only reads (default 5 seconds)
 * @param writeTimeout the time limit of each statement of a transaction that changes something (default 30 seconds);
 *        also what the background work (filling an index, emptying the recycle bin) runs under
 * @param overrides settings of single organizations, by the organization's identifier; only the numbers named change
 */
@ConfigurationProperties("platform.tenancy.limits")
record OrganizationLimitProperties(@DefaultValue("true") boolean enabled, @DefaultValue("6") int maxConcurrent,
        @DefaultValue("6") int maxWaiting, @DefaultValue("2s") Duration maxWait,
        @DefaultValue("5s") Duration queryTimeout, @DefaultValue("30s") Duration writeTimeout,
        @DefaultValue Map<String, Custom> overrides) {

    /**
     * The numbers of one organization that differ from the defaults.
     *
     * @param maxConcurrent requests at once, or null for the default
     * @param queryTimeout the reading limit, or null for the default
     * @param writeTimeout the changing limit, or null for the default
     */
    record Custom(Integer maxConcurrent, Duration queryTimeout, Duration writeTimeout) {
    }

    OrganizationLimitProperties {
        if (maxConcurrent < 1 || maxConcurrent > 1000 || maxWaiting < 0 || maxWaiting > 1000
                || maxWait.isNegative() || maxWait.compareTo(Duration.ofSeconds(30)) > 0
                || queryTimeout.compareTo(Duration.ofMillis(100)) < 0 || writeTimeout.compareTo(queryTimeout) < 0
                || writeTimeout.compareTo(Duration.ofMinutes(10)) > 0) {
            throw new IllegalArgumentException("platform.tenancy.limits: a number is outside its range (at least 1 "
                    + "request at once, a reading limit of at least 100 ms, a changing limit not below it)");
        }
        overrides = Map.copyOf(overrides);
    }
}
