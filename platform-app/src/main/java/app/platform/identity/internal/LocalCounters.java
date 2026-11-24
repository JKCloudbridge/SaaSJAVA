package app.platform.identity.internal;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Counters held in this instance's memory only. Used when Redis cannot be reached: each instance then limits for
 * itself, which is weaker across a group of instances but still bounds an attacker (ADR-0021). Memory is bounded: when
 * the table grows large the expired counters are dropped, and if it is still too large it is emptied (an emptied table
 * only means attackers get a fresh allowance, never that anybody is wrongly refused).
 */
final class LocalCounters implements Counters {

    private static final int SWEEP_ABOVE = 10_000;
    private static final int EMPTY_ABOVE = 200_000;

    private record Window(long count, long expiresAtMillis) {
    }

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final Clock clock;

    LocalCounters(Clock clock) {
        this.clock = clock;
    }

    @Override
    public long increment(String key, Duration window) {
        long now = clock.millis();
        if (windows.size() > SWEEP_ABOVE) {
            sweep(now);
        }
        return windows.compute(key, (k, existing) -> existing == null || existing.expiresAtMillis() <= now
                ? new Window(1, now + window.toMillis())
                : new Window(existing.count() + 1, existing.expiresAtMillis())).count();
    }

    @Override
    public long add(String key, Duration window, long amount) {
        long now = clock.millis();
        if (windows.size() > SWEEP_ABOVE) {
            sweep(now);
        }
        return windows.compute(key, (k, existing) -> existing == null || existing.expiresAtMillis() <= now
                ? new Window(amount, now + window.toMillis())
                : new Window(existing.count() + amount, existing.expiresAtMillis())).count();
    }

    @Override
    public long current(String key) {
        Window window = windows.get(key);
        return window == null || window.expiresAtMillis() <= clock.millis() ? 0 : window.count();
    }

    @Override
    public long remainingMillis(String key) {
        Window window = windows.get(key);
        return window == null ? 0 : Math.max(0, window.expiresAtMillis() - clock.millis());
    }

    private void sweep(long now) {
        windows.values().removeIf(window -> window.expiresAtMillis() <= now);
        if (windows.size() > EMPTY_ABOVE) {
            windows.clear();
        }
    }
}
