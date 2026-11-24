package app.platform.identity.internal;

import app.platform.sharedkernel.limits.RateLimitSettings;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The numbers of the limits in force for an organization (Sprint 32, ADR-0283): the platform's override where it set
 * one, else the default of each number. It is the one implementation of {@link RateLimitSettings}, which the platform
 * console calls inside an organization's own tenant context; the filter that counts requests reads through a short
 * cache, because a database read on every request of the public API would be a hot spot.
 *
 * <p>The cache holds numbers only, per organization, for {@code platform.limits.public-api.override-cache}. A change
 * made through this instance is seen at once by it; another instance sees it within that time. A read that fails is
 * answered with the defaults and is not cached: the limits must never refuse a request because the database was slow.
 */
@Component
class LimitOverrides implements RateLimitSettings {

    private static final int CACHE_BOUND = 5_000;

    private record Cached(Limits limits, long expiresAtMillis) {
    }

    private final LimitOverrideStore store;
    private final RateLimitProperties properties;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final ConcurrentHashMap<UUID, Cached> cache = new ConcurrentHashMap<>();

    LimitOverrides(LimitOverrideStore store, RateLimitProperties properties, TransactionTemplate transaction,
            Clock clock) {
        this.store = store;
        this.properties = properties;
        this.transaction = transaction;
        this.clock = clock;
    }

    @Override
    public Limits defaults() {
        return new Limits(properties.organizationPerMinute(), properties.organizationPerDay(),
                properties.applicationPerMinute(), false);
    }

    @Override
    public Limits effective() {
        return store.find().map(this::merged).orElseGet(this::defaults);
    }

    @Override
    public void set(Integer organizationPerMinute, Long organizationPerDay, Integer applicationPerMinute,
            UUID actor) {
        if (organizationPerMinute == null && organizationPerDay == null && applicationPerMinute == null) {
            throw new IllegalArgumentException("Give at least one number.");
        }
        RateLimitProperties.Ceilings ceilings = properties.ceilings();
        if (organizationPerMinute != null && (organizationPerMinute < 1 || organizationPerMinute > ceilings.perMinute())
                || applicationPerMinute != null
                && (applicationPerMinute < 1 || applicationPerMinute > ceilings.perMinute())
                || organizationPerDay != null && (organizationPerDay < 1 || organizationPerDay > ceilings.perDay())) {
            throw new IllegalArgumentException("A number is outside the range the platform allows.");
        }
        store.save(new LimitOverrideStore.Row(organizationPerMinute, organizationPerDay, applicationPerMinute),
                actor);
        cache.clear();
    }

    @Override
    public boolean clear(UUID actor) {
        boolean removed = store.remove(actor);
        cache.clear();
        return removed;
    }

    /**
     * The limits for the organization of the thread's tenant context, from the cache when it is fresh. Opens a
     * read-only transaction of its own on a miss. Never throws: a failed read gives the defaults.
     */
    Limits limitsFor(UUID tenant) {
        long now = clock.millis();
        Cached held = cache.get(tenant);
        if (held != null && held.expiresAtMillis() > now) {
            return held.limits();
        }
        try {
            Limits fresh = transaction.execute(status -> effective());
            if (fresh != null) {
                long ttl = properties.overrideCache().toMillis();
                if (ttl > 0) {
                    if (cache.size() > CACHE_BOUND) {
                        cache.clear();
                    }
                    cache.put(tenant, new Cached(fresh, now + ttl));
                }
                return fresh;
            }
        } catch (RuntimeException unreadable) {
            // The defaults, and nothing cached: the next request reads again.
        }
        return defaults();
    }

    private Limits merged(LimitOverrideStore.Row row) {
        Limits base = defaults();
        return new Limits(
                row.organizationPerMinute() == null ? base.organizationPerMinute() : row.organizationPerMinute(),
                row.organizationPerDay() == null ? base.organizationPerDay() : row.organizationPerDay(),
                row.applicationPerMinute() == null ? base.applicationPerMinute() : row.applicationPerMinute(), true);
    }
}
