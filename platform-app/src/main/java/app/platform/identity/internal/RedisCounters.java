package app.platform.identity.internal;

import java.time.Duration;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/**
 * Counters in Redis, shared by every instance. The increment and the start of the window are one script, so two
 * instances cannot both see "first" or leave a counter without an expiry. Throws when Redis cannot be reached;
 * {@link ResilientCounters} decides what happens then.
 */
final class RedisCounters implements Counters {

    private static final String PREFIX = "platform:identity:";
    private static final DefaultRedisScript<Long> INCREMENT = new DefaultRedisScript<>(
            "local c = redis.call('INCR', KEYS[1]) "
                    + "if c == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end "
                    + "return c", Long.class);

    private final StringRedisTemplate redis;

    RedisCounters(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public long increment(String key, Duration window) {
        Long count = redis.execute(INCREMENT, List.of(PREFIX + key), Long.toString(window.toMillis()));
        if (count == null) {
            throw new IllegalStateException("Redis returned no count");
        }
        return count;
    }

    @Override
    public long current(String key) {
        String value = redis.opsForValue().get(PREFIX + key);
        return value == null ? 0 : Long.parseLong(value);
    }
}
