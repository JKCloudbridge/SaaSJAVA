package app.platform.integration.internal;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * A count per source address and minute for the public receiver (Sprint 28, ADR-0221): it keeps an anonymous caller
 * from using the lookup of connections as a free loop. Held in memory, per instance, and bounded: the table is
 * cleared of past minutes whenever it grows, so an attacker with many addresses cannot make it grow without end. The
 * count of calls one <em>connection</em> may make belongs to Sprint 29, with the other limits of a connection.
 */
@Component
class InboundLimiter {

    private static final int MAX_SOURCES = 20_000;
    private static final long MINUTE = 60_000L;

    private record Window(long minute, AtomicInteger count) {
    }

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final int perMinute;
    private final Clock clock;

    InboundLimiter(IntegrationProperties properties, Clock clock) {
        this.perMinute = properties.flow().inboundPerMinutePerSource();
        this.clock = clock;
    }

    /** Counts a call and says whether the source is within its allowance for this minute. */
    boolean allow(String source) {
        long minute = clock.millis() / MINUTE;
        if (windows.size() > MAX_SOURCES) {
            windows.entrySet().removeIf(entry -> entry.getValue().minute() < minute);
            if (windows.size() > MAX_SOURCES) {
                return false;
            }
        }
        Window window = windows.compute(source, (key, old) -> old == null || old.minute() != minute
                ? new Window(minute, new AtomicInteger()) : old);
        return window.count().incrementAndGet() <= perMinute;
    }
}
