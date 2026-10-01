package app.platform.identity.internal;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The counters the rate limits use: Redis while it answers, this instance's memory while it does not (decision of
 * Sprint 3, ADR-0021). Sign-in keeps working when Redis is down, with limits that count per instance; the account lock
 * lives in the database and never depends on Redis.
 *
 * <p>After a failure Redis is left alone for {@code pause}, so a broken Redis costs one slow call per pause and not one
 * per request. The fall-back is made visible: one warning when it starts, one when Redis is back, and a counter
 * ({@code platform.identity.ratelimit.degraded}) of the operations that were answered locally, which is what an alert
 * should watch.
 */
final class ResilientCounters implements Counters {

    private static final Logger LOG = LoggerFactory.getLogger(ResilientCounters.class);

    private final Counters shared;
    private final Counters local;
    private final Clock clock;
    private final long pauseMillis;
    private final MeterRegistry meters;
    private final AtomicLong pausedUntil = new AtomicLong(0);
    private volatile boolean degraded;

    ResilientCounters(Counters shared, Counters local, Clock clock, Duration pause, MeterRegistry meters) {
        this.shared = shared;
        this.local = local;
        this.clock = clock;
        this.pauseMillis = pause.toMillis();
        this.meters = meters;
    }

    @Override
    public long increment(String key, Duration window) {
        if (sharedAvailable()) {
            try {
                long count = shared.increment(key, window);
                recovered();
                return count;
            } catch (RuntimeException e) {
                failed(e);
            }
        }
        meters.counter("platform.identity.ratelimit.degraded").increment();
        return local.increment(key, window);
    }

    @Override
    public long current(String key) {
        if (sharedAvailable()) {
            try {
                long count = shared.current(key);
                recovered();
                return count;
            } catch (RuntimeException e) {
                failed(e);
            }
        }
        meters.counter("platform.identity.ratelimit.degraded").increment();
        return local.current(key);
    }

    private boolean sharedAvailable() {
        return clock.millis() >= pausedUntil.get();
    }

    private void failed(RuntimeException e) {
        pausedUntil.set(clock.millis() + pauseMillis);
        if (!degraded) {
            degraded = true;
            // Only the exception type: the message of a client library may carry connection details.
            LOG.warn("Redis is not reachable ({}): sign-in rate limits now count per instance until it returns",
                    e.getClass().getSimpleName());
        }
    }

    private void recovered() {
        if (degraded) {
            degraded = false;
            LOG.warn("Redis is reachable again: sign-in rate limits are shared across instances again");
        }
    }
}
